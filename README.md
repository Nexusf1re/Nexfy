# Nexfy

Cliente de Spotify para Android, rápido e fluido: o áudio e o Spotify Connect vêm de um núcleo em Rust ([librespot](https://github.com/librespot-org/librespot)), e a interface é Jetpack Compose com vidro, movimento e transições pensadas para 120 Hz.

> O Nexfy não tem relação com o Spotify. É preciso uma conta **Spotify Premium**.

## O que faz

- Toca no próprio celular e aparece como dispositivo do Spotify Connect: dá para mandar música do PC, da TV ou da caixa de som para ele.
- Controla os outros dispositivos da conta: transferir a música, play, pausa, pular, volume, fila.
- Biblioteca, playlists, álbuns, artistas, busca, músicas curtidas, ordenação e busca dentro das listas.
- Fila do Spotify, adicionar à fila arrastando a música para o lado.
- Letras sincronizadas sobre a capa desfocada, também em tela cheia (Spotify, com LRCLIB como reserva).
- Músicas no aparelho: no Wi-Fi, baixa as próximas da fila e as playlists que você mantiver no aparelho (cache criptografado; ainda precisa de internet para tocar).
- Widget na tela inicial, notificação e controles de mídia do Android.

## Instalar

Baixe o APK mais recente em [Releases](../../releases) e instale (permita instalar de fontes desconhecidas). Android 12 ou mais novo, arm64.

Na primeira abertura, em **Ajustes**:

1. **Reprodução:** entre com a conta Spotify (Premium).
2. **App pessoal:** crie um app em [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard) com o Redirect URI `http://127.0.0.1:8989/login`, cole o Client ID e entre. Ele dá acesso à biblioteca, à busca e ao controle de outros dispositivos.

## Compilar

Requisitos: Android Studio (SDK 36 e NDK `30.0.16248370`), Rust com o alvo `aarch64-linux-android` e o `cargo-ndk`:

```powershell
rustup target add aarch64-linux-android
cargo install cargo-ndk --version 4.1.2 --locked
.\gradlew.bat :app:assembleRelease
```

O APK sai em `app/build/outputs/apk/release/`. O Gradle compila o núcleo em Rust e gera as ligações Kotlin (UniFFI) sozinho. Sem uma chave de release configurada, o APK é assinado com a chave de depuração.

### Estrutura

- `core/`: núcleo em Rust: login, sessão e reprodução (librespot), Spotify Connect, Web API, letras e cache. Exposto ao Kotlin via UniFFI.
- `core/third_party/librespot/`: cópia do librespot com pequenos ajustes para Android.
- `app/`: app Android em Kotlin e Jetpack Compose.

## Releases

Cada tag `v*` gera, pelo GitHub Actions, o APK assinado e a página de release com as mudanças:

```powershell
git tag v0.2.0
git push origin v0.2.0
```

## Contribuir

Contribuições são bem-vindas, em português ou inglês. Leia o [CONTRIBUTING](CONTRIBUTING.md) e abra uma issue antes de começar algo grande. Problemas de segurança vão pelo canal privado descrito em [SECURITY](SECURITY.md).

## Licença

[GPL-3.0](LICENSE). O librespot em `core/third_party/librespot/` mantém a licença MIT dele.

## Créditos

- [librespot](https://github.com/librespot-org/librespot) (MIT), a base de reprodução e do Spotify Connect.
- [Spotifast](https://github.com/crmne/spotifast), a inspiração de conectividade, e o fork do librespot usado aqui.
