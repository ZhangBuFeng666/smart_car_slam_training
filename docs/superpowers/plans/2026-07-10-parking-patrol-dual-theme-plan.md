# Parking Patrol Dual-Theme Implementation Plan

1. Add failing JVM tests for destination order, theme defaults/palette roles, home invariance, and drive interaction metrics.
2. Implement the pure Kotlin theme specification and persistence adapter.
3. Update primary navigation from `tasks` to `ai` while keeping existing HTTP and ROS keys.
4. Add palette-aware rendering to the custom parking map and vision views.
5. Build the Light-mode Drive, AI, Vision, and Navigation surfaces.
6. Map the same surfaces to the existing black-and-gold Dark palette.
7. Add the theme toggle and persist/re-render behavior.
8. Run JVM tests, Android Lint, and debug APK assembly.
9. Install on the connected phone and capture Home plus four pages in both modes.
10. Compare Home with the approved screenshot, inspect text/controls/overlap, and fix regressions.

