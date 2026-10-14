//! Playlist changes over the librespot session instead of the Web API.
//!
//! A development-mode app gets a small daily Web API quota; the playlist
//! service Spotify's own clients write to has none. Each change is a
//! ListChanges of ops (ADD, REM, UPDATE_LIST_ATTRIBUTES) made on the
//! revision just read, so the service can place index-based ops even when
//! another device changed the list in between.

use anyhow::{Result, anyhow};
use librespot_core::SpotifyId;
use librespot_core::session::Session;
use librespot_protocol::playlist4_external::{
    Add, CreateListReply, Delta, Item, ItemAttributes, ListAttributeKind, ListAttributes, ListAttributesPartialState,
    ListChanges, Op, OpList, Rem, SelectedListContent, UpdateListAttributes, op, source_info,
};
use protobuf::{EnumOrUnknown, Message, MessageField};

use crate::webapi::{Playlist, WebApi};

/// Items asked for per read when looking for what to remove.
const PAGE: usize = 500;

/// A playlist, or the user's rootlist (the playlists in their library).
enum List {
    Playlist(SpotifyId),
    Rootlist,
}

/// A list's items as of `revision`, in order.
struct Snapshot {
    revision: Vec<u8>,
    length: usize,
    items: Vec<Item>,
}

impl List {
    fn playlist(id: &str) -> Result<Self> {
        SpotifyId::from_base62(id).map(List::Playlist).map_err(|e| anyhow!("playlist {id}: {e}"))
    }

    async fn page(&self, session: &Session, from: usize, length: usize) -> Result<SelectedListContent> {
        let spclient = session.spclient();
        let bytes = match self {
            List::Playlist(id) => spclient.get_playlist_range(id, from, length).await?,
            List::Rootlist => spclient.get_rootlist(from, Some(length)).await?,
        };
        Ok(SelectedListContent::parse_from_bytes(&bytes)?)
    }

    /// The revision and length; with `whole`, every item too.
    async fn read(&self, session: &Session, whole: bool) -> Result<Snapshot> {
        let first = self.page(session, 0, if whole { PAGE } else { 1 }).await?;
        let revision = first.revision().to_vec();
        let length = first.length().max(0) as usize;
        let contents = first.contents.into_option().unwrap_or_default();
        let mut more = whole && contents.truncated();
        let mut items = contents.items;
        while whole && (more || items.len() < length) {
            let next = self.page(session, items.len(), PAGE).await?;
            // Indexes from two revisions would point at the wrong items.
            if next.revision() != revision.as_slice() {
                return Err(anyhow!("a lista mudou durante a leitura"));
            }
            let contents = next.contents.into_option().unwrap_or_default();
            if contents.items.is_empty() {
                break;
            }
            more = contents.truncated();
            items.extend(contents.items);
        }
        Ok(Snapshot { revision, length, items })
    }

    async fn send(&self, session: &Session, revision: Vec<u8>, ops: Vec<Op>) -> Result<()> {
        let changes = ListChanges {
            base_revision: Some(revision.clone()),
            deltas: vec![Delta {
                base_version: Some(revision),
                ops,
                info: MessageField::some(change_info()),
                ..Default::default()
            }],
            ..Default::default()
        };
        let spclient = session.spclient();
        match self {
            List::Playlist(id) => spclient.change_playlist(id, &changes).await?,
            List::Rootlist => spclient.change_rootlist(&changes).await?,
        };
        Ok(())
    }
}

/// The source the web player names, whose JSON form these changes take.
fn change_info() -> librespot_protocol::playlist4_external::ChangeInfo {
    let mut info = librespot_protocol::playlist4_external::ChangeInfo::new();
    info.source.mut_or_insert_default().client = Some(EnumOrUnknown::new(source_info::Client::WEBPLAYER));
    info
}

fn now_ms() -> i64 {
    librespot_core::date::Date::now_utc().as_timestamp_ms()
}

/// A new entry; `public` is whether a rootlist entry shows on the profile.
fn new_item(uri: &str, public: Option<bool>) -> Item {
    Item {
        uri: Some(uri.to_owned()),
        attributes: MessageField::some(ItemAttributes { timestamp: Some(now_ms()), public, ..Default::default() }),
        ..Default::default()
    }
}

fn add_op(add: Add) -> Op {
    Op { kind: Some(EnumOrUnknown::new(op::Kind::ADD)), add: MessageField::some(add), ..Default::default() }
}

fn attributes_op(values: ListAttributes, no_value: Vec<ListAttributeKind>) -> Op {
    let update = UpdateListAttributes {
        new_attributes: MessageField::some(ListAttributesPartialState {
            values: MessageField::some(values),
            no_value: no_value.into_iter().map(EnumOrUnknown::new).collect(),
            ..Default::default()
        }),
        ..Default::default()
    };
    Op {
        kind: Some(EnumOrUnknown::new(op::Kind::UPDATE_LIST_ATTRIBUTES)),
        update_list_attributes: MessageField::some(update),
        ..Default::default()
    }
}

/// A REM for each run of items to remove, the last run first: removing it
/// leaves the indexes of the runs before it as read.
fn removals(items: &[Item], remove: impl Fn(&str) -> bool) -> Vec<Op> {
    let mut ops = Vec::new();
    let mut i = 0;
    while i < items.len() {
        if !remove(items[i].uri()) {
            i += 1;
            continue;
        }
        let start = i;
        while i < items.len() && remove(items[i].uri()) {
            i += 1;
        }
        // The items let the service check the indexes still point at them;
        // the item id tells repeated songs apart.
        let removed = items[start..i]
            .iter()
            .map(|item| {
                let id = item.attributes.item_id().to_vec();
                Item {
                    uri: Some(item.uri().to_owned()),
                    attributes: if id.is_empty() {
                        MessageField::none()
                    } else {
                        MessageField::some(ItemAttributes { item_id: Some(id), ..Default::default() })
                    },
                    ..Default::default()
                }
            })
            .collect();
        ops.push(Op {
            kind: Some(EnumOrUnknown::new(op::Kind::REM)),
            rem: MessageField::some(Rem {
                from_index: Some(start as i32),
                length: Some((i - start) as i32),
                items: removed,
                ..Default::default()
            }),
            ..Default::default()
        });
    }
    ops.reverse();
    ops
}

/// Adds songs at the end, or from `position` on.
pub async fn add(session: &Session, id: &str, uris: &[String], position: Option<u32>) -> Result<()> {
    if uris.is_empty() {
        return Ok(());
    }
    let list = List::playlist(id)?;
    let head = list.read(session, false).await?;
    let mut add = Add { items: uris.iter().map(|uri| new_item(uri, None)).collect(), ..Default::default() };
    match position {
        Some(at) if (at as usize) < head.length => add.from_index = Some(at as i32),
        _ => add.add_last = Some(true),
    }
    list.send(session, head.revision, vec![add_op(add)]).await
}

/// Removes every occurrence of these songs.
pub async fn remove(session: &Session, id: &str, uris: &[String]) -> Result<()> {
    let list = List::playlist(id)?;
    let snapshot = list.read(session, true).await?;
    let ops = removals(&snapshot.items, |uri| uris.iter().any(|u| u == uri));
    if ops.is_empty() {
        return Ok(());
    }
    list.send(session, snapshot.revision, ops).await
}

/// Renames and redescribes; an empty description clears it.
pub async fn edit(session: &Session, id: &str, name: &str, description: &str) -> Result<()> {
    let list = List::playlist(id)?;
    let head = list.read(session, false).await?;
    let mut values = ListAttributes::new();
    if !name.trim().is_empty() {
        values.set_name(name.to_owned());
    }
    let mut cleared = Vec::new();
    if description.is_empty() {
        cleared.push(ListAttributeKind::LIST_DESCRIPTION);
    } else {
        values.set_description(description.to_owned());
    }
    list.send(session, head.revision, vec![attributes_op(values, cleared)]).await
}

/// A new private playlist, put at the top of the library as Spotify's own
/// clients do: the playlist service alone leaves it in no library.
pub async fn create(session: &Session, name: &str, description: &str) -> Result<Playlist> {
    let mut values = ListAttributes::new();
    values.set_name(name.to_owned());
    if !description.is_empty() {
        values.set_description(description.to_owned());
    }
    let ops = OpList { ops: vec![attributes_op(values, Vec::new())], ..Default::default() };
    let reply = session.spclient().create_playlist(&ops).await?;
    let uri = created_uri(&reply)?;
    let id = uri.strip_prefix("spotify:playlist:").ok_or_else(|| anyhow!("resposta inesperada: {uri}"))?.to_owned();

    let root = List::Rootlist;
    let head = root.read(session, false).await?;
    let add = Add { items: vec![new_item(&uri, Some(false))], add_first: Some(true), ..Default::default() };
    root.send(session, head.revision, vec![add_op(add)]).await?;

    let me = session.username();
    Ok(Playlist {
        id,
        uri,
        name: name.to_owned(),
        description: description.to_owned(),
        owner: "você".to_owned(),
        owner_id: me,
        image_url: None,
        track_count: 0,
        followers: 0,
    })
}

/// The new playlist's URI: JSON as asked for, or a CreateListReply.
fn created_uri(reply: &[u8]) -> Result<String> {
    let json = serde_json::from_slice::<serde_json::Value>(reply).ok();
    let uri = match json.as_ref().and_then(|v| v.get("uri")).and_then(|v| v.as_str()) {
        Some(uri) => uri.to_owned(),
        None => CreateListReply::parse_from_bytes(reply)?.uri().to_owned(),
    };
    if uri.is_empty() {
        return Err(anyhow!("o Spotify não devolveu a playlist criada"));
    }
    Ok(uri)
}

/// Puts `spotify:playlist:` URIs into the library, or with `on` false
/// takes them out. Taking out one's own playlist is how Spotify deletes it.
pub async fn set_followed(session: &Session, uris: &[String], on: bool) -> Result<()> {
    let root = List::Rootlist;
    let snapshot = root.read(session, true).await?;
    let ops = if on {
        let mut missing: Vec<&String> = Vec::new();
        for uri in uris {
            if !missing.contains(&uri) && !snapshot.items.iter().any(|item| item.uri() == uri) {
                missing.push(uri);
            }
        }
        if missing.is_empty() {
            Vec::new()
        } else {
            let items = missing.into_iter().map(|uri| new_item(uri, None)).collect();
            vec![add_op(Add { items, add_first: Some(true), ..Default::default() })]
        }
    } else {
        removals(&snapshot.items, |uri| uris.iter().any(|u| u == uri))
    };
    if ops.is_empty() {
        return Ok(());
    }
    root.send(session, snapshot.revision, ops).await
}

/// After a change to a playlist: its next read, and the library's track
/// counts, come fresh from either the Web API or the session.
pub fn forget_playlist(web: &WebApi, id: &str) {
    web.forget_playlist(id);
    web.forget(&format!("session:playlist3:{id}"));
    web.forget("session:rootlist");
}

/// After a change to the library's playlists.
pub fn forget_rootlist(web: &WebApi) {
    web.forget("/me/playlists?limit=50");
    web.forget("session:rootlist");
}

#[cfg(test)]
mod tests {
    use super::*;

    fn items(uris: &[&str]) -> Vec<Item> {
        uris.iter().map(|uri| new_item(uri, None)).collect()
    }

    #[test]
    fn removals_cover_every_occurrence_last_run_first() {
        let list = items(&["a", "b", "b", "c", "b", "a"]);
        let ops = removals(&list, |uri| uri == "b");
        let runs: Vec<(i32, i32)> = ops.iter().map(|op| (op.rem.from_index(), op.rem.length())).collect();
        assert_eq!(runs, vec![(4, 1), (1, 2)]);
        assert!(ops.iter().all(|op| op.kind.map(|k| k.enum_value()) == Some(Ok(op::Kind::REM))));
        assert!(ops[1].rem.items.iter().all(|item| item.uri() == "b"));
    }

    #[test]
    fn nothing_to_remove_is_no_op() {
        assert!(removals(&items(&["a", "c"]), |uri| uri == "b").is_empty());
    }

    #[test]
    fn changes_serialize_with_their_required_fields() {
        let mut values = ListAttributes::new();
        values.set_name("Nova".to_owned());
        let ops = OpList { ops: vec![attributes_op(values, vec![ListAttributeKind::LIST_DESCRIPTION])], ..Default::default() };
        // write_to_bytes refuses a message missing a required field.
        let bytes = ops.write_to_bytes().unwrap();
        let back = OpList::parse_from_bytes(&bytes).unwrap();
        let state = &back.ops[0].update_list_attributes.new_attributes;
        assert_eq!(state.values.name(), "Nova");
        assert_eq!(state.no_value.len(), 1);
    }
}
