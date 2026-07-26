# Changelog

Notable changes, generated from [conventional commits](https://www.conventionalcommits.org) by
git-cliff. Do not edit by hand.
## Unreleased

### Bug Fixes
- fmt drift, orphaned doc comments, and Android tests that raced the network (dc44ef4)
- two bugs in my own one-time-prekey work, plus three CI breaks (93859f8)
- pin the correct Gradle 9.6.1 distribution SHA-256 (2dbc987)
- root-cause + close the bearer-lan JaCoCo coverage wobble (F-3) (#103) (5c2045f)

### CI
- bump create-github-app-token to v3.2.0 across all mirrored components (efc9f6c)

### Chore
- bump com.android.library in /bearers/android (65ed034)
- purge em-dashes and en-dashes from source (d222435)
- bump org.robolectric:robolectric in /apps/android/HopDemo (#110) (89cb246)
- bump androidx.test:core in /bearers/android (#113) (df1e0e0)
- drop the root license, license per-component (FSL-1.1-ALv2) (#146) (be2a5a7)
- finish the monorepo layout, kill platform stubs, unify the platform axis (O-1/O-3/O-4/O-5) (#115) (b56bb49)
- organize the monorepo, all apps under apps/<platform>/<App> + a CLAUDE.md at every tree node (#105) (73c3249)
- bump net.java.dev.jna:jna in /bearers/android (#32) (257bdea)

### Dependencies
- AGP 9.3 + Gradle 9.6.1 + Kotlin 2.4.10 + core-ktx 1.19.0 (13 held bumps) (471edb7)
- Kotlin 2.4/AGP 9.2.1/Compose BOM 2026.06/okhttp 5.4 toolchain migration (#90) (d4844bd)

### Documentation
- regenerate from conventional commits (1572ae2)
- regenerate from conventional commits (a355901)
- branded, marketable READMEs for every sub-repo (9c2a477)

### Features
- finish inbound (import), drop export_pr (41c095e)
- auto-generate monorepo + per-library changelogs (git-cliff) (8c64c37)

### Other
- move the BLE socket write off the node's core thread (R-02) (41fd3fb)
- cut the frame cap to the protocol's, and pin it (2a16945)
- delete unwired trait surface and correct 12 stale claims (4e4272f)
- wire the relay pool end to end, and stop the wire guard false-firing (35946e0)
- CLA gate on contributions (preserve commercial relicensing of core) (5a9aa7d)
- SECURITY.md per component + enable-security in the bootstrap script (a1492e9)
- copyright holder is Hop Mesh, LLC (7d8c514)
- fill the Apache-2.0 copyright placeholder (2026 Jason Waldrip) (2fb7d1c)
- Apache-2.0 for everything except core/ (only the protocol stays FSL) (0fe9439)
- CHANGE_REQUEST sync-back + document merge/conversation + confidentiality (9e1dec2)
- route dedup through the pure keep-rule cores; fix inverted Android dedup-ordering docs (#72) (8a083a1)
- fix Android dial-backoff wedge caught by the hardware gauntlet (#16) (bc47eab)
- remove the Wi-Fi Direct bearer (per-device approval dialog) (c059d69)
- refresh GATT cache on dial timeout to break the discovery wedge (94e0315)
- app identifiers + code packages net.waldrip.* / co.hopmesh.* -> sh.hopme.* (9050fbd)
- JNA compileOnly to avoid jar+aar duplicate-class clash (595cf3a)
- thin HopDriver composing the SDK + all four bearers (mirror of Apple) (171b04a)
- re-home all four bearers as independent modules on the Kotlin SDK (fd72ebe)

### Refactor
- enforce purpose/platform/package (collapse sdk/wrappers, apps/web -> apps/web/site) (#116) (afd52df)

### Testing
- LAN 95% / Relay 100% / BLE 99% + fix non-restartable LanBearer (#68) (0fab82d)

