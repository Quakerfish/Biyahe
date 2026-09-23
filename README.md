# BIYAHE Android — refreshed build

This is your app rebuilt with the same screens and the same overall size/shape,
plus a working **light/dark/system appearance toggle** and a round of UI and
functionality polish.

## How to use this

Unzip this over (or diff it against) your existing Android Studio project —
package name and file names all match `com.biyahe.app`. The 9 files you
originally sent me are all here, improved. Everything else in this zip
(`colors.xml`, `styles.xml`, `strings.xml`, the icon set, `bottom_nav_menu.xml`,
`AndroidManifest.xml`, the Gradle files, the launcher icon) **did not come with
your upload** — only the layouts and Kotlin files referenced them by name. I
rebuilt all of it from those references so this project is complete and
buildable on its own, but if your real project already has better versions of
these (real icon art, your actual manifest, your actual app icon), **keep
yours** and just take the files under "What actually changed" below.

## What actually changed

**Dark mode (the main ask)**
- `values/colors.xml` + `values-night/colors.xml` — every color in the app is
  now a named token (`bg_app`, `bg_white`, `text_primary`, `brand_orange`,
  etc.) with a dark-mode override. Nothing is hardcoded anymore — I found and
  fixed the one place that was (`item_route_display.xml` had `#F26522`,
  `#111827`, `#9CA3AF` baked in, which is why that screen would have stayed
  stuck in light mode).
- `values/themes.xml` + `values-night/themes.xml` — `Theme.Biyahe` extends
  `Theme.MaterialComponents.DayNight`, with matching status/nav bar colors.
- `ThemeManager.kt` (new) — stores the user's Light / Dark / System choice in
  `SharedPreferences` and applies it via `AppCompatDelegate`.
- `BaseActivity.kt` (new) — every activity now extends this instead of
  `AppCompatActivity` directly; it applies the saved theme before the window
  inflates so there's no flash of the wrong theme.
- **Profile screen** got a new "Appearance" row (between Transit Preferences
  and Notifications) that opens a Light / Dark / System picker and shows the
  current choice.
- One thing dark mode does *not* touch: the MapLibre basemap on the Home
  screen is a fixed light vector-tile style from MapTiler. I left a comment in
  `MainActivity.kt` where you'd swap in a dark style id if your MapTiler plan
  has one — that's a one-line change once you have that id, not something I
  could safely guess.

**UI polish**
- Routes list now shows a loading spinner while fetching and a proper empty
  state ("No routes found") instead of just sitting blank.
- Search on the Routes screen is debounced (150ms) so fast typing doesn't
  re-filter on every keystroke.
- Added autofill hints (`username`, `password`, `emailAddress`, etc.) and
  proper `imeOptions` (Next/Done) across Login, Signup, and Auth so the
  keyboard flows naturally and password managers work.
- Filled in a full icon set (`ic_home`, `ic_route`, `ic_back`, `ic_person`,
  etc.) and a `bottom_nav_menu.xml` — see the note above about keeping your
  real ones instead if you have them.

**Functionality / robustness**
- Every `HttpURLConnection` now `disconnect()`s in a `finally` block (the
  originals never did, which leaks connections over time).
- Login/Signup: inputs are trimmed and re-validated on every submit attempt,
  field errors are cleared before re-checking, and the submit button disables
  itself with a "Logging in…" / "Creating account…" state while a request is
  in flight so a double-tap can't fire two requests.
- Network failures now show one consistent message
  (`BaseActivity.showNetworkError`) instead of leaking raw exception text to
  the user, with a distinct message for timeouts.
- `RouteAdapter` / `RouteSuggestionAdapter` swapped `notifyDataSetChanged()`
  for `DiffUtil`, so filtering the list while searching no longer re-binds
  and flickers every row.
- Bottom nav's Profile tab now actually navigates to `ProfileActivity`
  everywhere (it previously showed a "coming soon" toast on some screens even
  though the screen existed).
- Logout now also clears the stored session cookie, not just the redirect.
- Flagged the EmailJS keys in `ApiConfig.kt` with a `TODO(security)` — they're
  shipped in plaintext in the APK today; worth moving OTP-sending server-side
  when you get a chance, not something I changed since it'd require a backend
  change too.

## What I did *not* change
- No new screens, no restructured navigation — same size, same flow, per your
  ask.
- Business logic (routing, waypoint fetching, session handling) is untouched
  besides the robustness fixes above.
- I didn't touch `Biyahe-Admin` (the PHP web project) — this is Android only.
