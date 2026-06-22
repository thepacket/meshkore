# Third-party dependencies

| Dependency | License | Use |
|---|---|---|
| [Nordic Android-BLE-Library](https://github.com/NordicSemiconductor/Android-BLE-Library) (`no.nordicsemi.android:ble`, `ble-ktx`) | BSD-3-Clause | BLE connection state machine over the companion's Nordic UART Service |
| [AndroidX / Jetpack Compose](https://developer.android.com/jetpack/androidx) | Apache-2.0 | UI toolkit, lifecycle, activity |
| [Kotlin & kotlinx.coroutines](https://github.com/JetBrains/kotlin) | Apache-2.0 | Language + async |
| [JUnit 4](https://junit.org/junit4/) | EPL-1.0 (test only) | `core-protocol` unit tests |

All runtime dependencies are MIT-compatible (BSD-3-Clause / Apache-2.0). No GPL code
is pulled in, consistent with the project's MIT licensing.

The MeshCore companion frame protocol implemented in `core-protocol` is derived by
inspection of the open-source [MeshCore](https://github.com/meshcore-dev/MeshCore)
firmware (MIT). No MeshCore source code is copied into this repository.
