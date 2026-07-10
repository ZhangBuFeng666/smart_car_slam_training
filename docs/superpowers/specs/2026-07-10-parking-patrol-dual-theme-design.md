# Parking Patrol Dual-Theme UI Design

## Scope

Upgrade four Android app pages while preserving the current patrol home page:

- Keep the patrol home page layout and behavior unchanged while allowing its colors to follow the selected theme.
- Replace the bottom `tasks` destination with a full `ai` destination.
- Redesign Drive, AI, Vision, and Navigation around one dominant operational surface per page.
- Add user-selectable Light and Dark modes.
- Use white, ice blue, sky blue, mint, and neutral charcoal in Light mode.
- Preserve the current black-and-gold language in Dark mode.
- Keep all existing HTTP URLs, ROS task keys, emergency controls, and movement safety behavior.

No commit or push is allowed until the user tests and approves the version.

## Information Architecture

The bottom destinations are:

1. Patrol (`home`)
2. Drive (`drive`)
3. AI (`ai`)
4. Vision (`vision`)
5. Navigation (`nav`)

The previous `tasks` page is removed from primary navigation. Its useful service controls remain reachable from the AI plan, Drive service row, and the relevant Vision or Navigation tools.

## Theme Behavior

The app stores one mode: `light` or `dark`.

- The first launch defaults to Light mode.
- The user can switch mode from a compact sun/moon action in the top-right of all four redesigned pages.
- The selected mode is stored in `SharedPreferences` and restored on the next launch.
- Switching mode re-renders the active page without restarting the activity.
- The patrol home page keeps its current composition and 3D vehicle, but uses the selected Light or Dark palette.
- When leaving Home, the bottom navigation and system bars use the selected mode.

## Page Designs

### Drive

Use a single integrated control deck instead of stacked cards. Retain physical-looking buttons because explicit directional targets are safer and easier to use while watching the vehicle.

- Six directional buttons: forward, back, strafe left/right, rotate left/right.
- Buttons have layered fill, border, shadow/elevation, pressed scale, and active color feedback.
- Holding a direction starts movement and repeats commands.
- Releasing or cancelling sends `stop` immediately.
- Emergency stop remains directly visible.
- Speed presets and slider remain available in a compact strip.
- Base-driver status is a lightweight row below the main deck.

### AI

AI becomes a first-class page rather than a bottom sheet.

- Natural-language mission input at the top.
- Parking-lot examples remain available as compact prompts.
- Generated steps appear as a vertical timeline.
- The page clearly states that it generates a preview and does not directly control the vehicle yet.
- Emergency stop remains visible.
- Existing compound patrol examples are preserved.

### Vision

The camera preview occupies the main visual area.

- Detection overlays remain inside the preview.
- Camera and HSV actions float directly below or over the preview.
- Occupancy and warning counts become compact overlay pills.
- Recognition classes and service state become lightweight rows, not framed cards.
- Simulated/local preview remains explicitly labelled until live video is connected.

### Navigation

The parking map occupies the main visual area.

- Checkpoints remain interactive.
- Route status appears in a floating map panel.
- Mapping, map display/save, Nav2 preparation, goal setting, and DWA patrol remain available.
- Tool actions use a structured list or bottom sheet instead of a two-column card grid.
- The local draft-map label remains visible until ROS map data is connected.

## Architecture

- `ParkingThemeSpec`: pure Kotlin theme mode, palette, and persistence key definitions for all five pages.
- `ParkingThemeStore`: Android `SharedPreferences` adapter.
- `MainActivity`: owns page selection, HTTP actions, movement lifecycle, and integration.
- `ParkingMapView` and `ParkingVisionView`: receive palette updates so custom drawing follows the selected mode.
- Page-specific view builders may be split into dedicated Kotlin files where callback boundaries are clear.

## Safety And Compatibility

- Do not change `CarApi` URL contracts.
- Do not change ROS task keys.
- Do not remove emergency stop or stop-all behavior.
- Preserve the movement `ACTION_DOWN` start and `ACTION_UP`/`ACTION_CANCEL` stop contract.
- Theme changes must not stop or start robot services.
- The home 3D WebView lifecycle and appearance must remain unchanged.

## Testing

JVM tests cover:

- Primary destination order includes `ai` and excludes `tasks`.
- Light is the default theme.
- Theme mode parsing falls back safely.
- Light and Dark palettes have expected contrast roles.
- Home is marked theme-aware while its layout metrics remain fixed.
- Drive button size and press/release safety specification remain intact.

Device verification covers:

- Theme switching and persistence after process restart.
- Home layout is unchanged and is inspected in both Light and Dark modes.
- All four redesigned pages in Light and Dark modes.
- Direction button press/release feedback.
- No overlap at the connected phone resolution.
- Existing HTTP actions still show progress, success, and failure feedback.
