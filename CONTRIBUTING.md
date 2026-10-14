# Contributing to Nexfy

Thanks for helping. Issues and pull requests are welcome in English or Portuguese.

## Before you start

- **Bugs:** open an issue with the bug template. Include the Nexfy version, the phone and Android version, and what you did.
- **Features:** open an issue first so we can agree on the idea before you write code. Nexfy keeps a clean, uncluttered UI, so not every feature fits.
- **Small fixes** (typos, obvious bugs) can go straight to a pull request.

## Building

You need Android Studio (SDK 36, NDK `30.0.16248370`), Rust with the `aarch64-linux-android` target and `cargo-ndk`:

```powershell
rustup target add aarch64-linux-android
cargo install cargo-ndk --version 4.1.2 --locked
.\gradlew.bat :app:assembleDebug
```

Gradle builds the Rust core and generates the UniFFI Kotlin bindings by itself. Without a release key the APK is signed with the debug key. To use your own Spotify app, follow the setup steps in the [README](README.md#instalar).

Run the core tests with:

```powershell
cargo test --manifest-path core/Cargo.toml
```

## Layout

- `core/`: Rust core (login, session, playback through librespot, Spotify Connect, Web API, lyrics, cache), exposed to Kotlin through UniFFI.
- `core/third_party/librespot/`: vendored librespot with small Android patches. Keep changes here minimal and explain them in the commit.
- `app/`: Android app in Kotlin and Jetpack Compose.

## Pull requests

- One topic per pull request. Keep the diff focused.
- Match the style of the surrounding code: naming, comment density, idioms.
- Test on a real device when you touch playback, Connect or audio output.
- For UI changes, add a screenshot or a short recording.
- Make sure CI passes (build and core tests).

### Commit messages

English, in the form `type(scope): summary`, with a short body saying why:

```
fix(audio): reopen the output when Android moves it to another device

Android closes the stream when the route changes, and the sink then
waited forever for it to drain.

- detect a stalled output and open it again
```

Common types: `feat`, `fix`, `perf`, `refactor`, `docs`, `build`, `ci`, `chore`.

## Ground rules

- Never commit credentials, keystores, tokens or `local.properties`.
- Nexfy needs Spotify Premium and must not help bypass it, download tracks outside the encrypted cache, or remove ads from other clients.
- By contributing you agree that your work is licensed under the [GPL-3.0](LICENSE).

Please follow the [Code of Conduct](CODE_OF_CONDUCT.md).
