# Knight app icon

The mark is a helmet seen from the front: one filled silhouette with the visor
slot knocked out. It reads as the knight in the name and as the thing you put on
before you ride.

## Files

| File | Goes in |
|---|---|
| `ic_launcher_foreground.xml` | `res/drawable/` |
| `ic_launcher_background.xml` | `res/drawable/` |
| `ic_launcher_monochrome.xml` | `res/drawable/` |
| `ic_launcher.xml` | `res/mipmap-anydpi-v26/`, copied to `ic_launcher_round.xml` as well |
| `icon-512.svg` | source for the Play listing, export to 512x512 PNG |

Point the manifest at it:

```xml
<application
    android:icon="@mipmap/ic_launcher"
    android:roundIcon="@mipmap/ic_launcher_round">
```

## Geometry

Drawn on the standard 108 x 108 dp adaptive canvas.

- The mark occupies 44 x 48 dp, centred at 54,54. Its bounding circle is 65 dp
  across, just inside the 66 dp key shape every launcher mask is guaranteed to
  show. An earlier draft was 54.6 x 59.8 dp, which measured 81 dp corner to
  corner and crowded the circle mask.
- The dome is wider than the chin and the slot sits above centre. Draw it as a
  symmetric rounded box with a centred bar and it stops reading as a helmet and
  starts reading as a minus sign.
- The visor slot is 32 x 9 dp with fully rounded ends. It is the first feature
  to close up at small sizes, so do not thin it when rescaling.
- `fillType="evenOdd"` is what cuts the slot out. Without it the slot fills in
  and the icon becomes a blob.

## Colour

- Mark `#FF9F0A`, the same amber the app uses for phone-to-console traffic.
- Background `#0B0B0B`, **not** `#000000`.

The background is deliberately not pure black. A true black tile disappears into
a dark wallpaper and the amber mark floats with no edge, so the app loses its
silhouette in the launcher grid. `#0B0B0B` still reads as black beside the app
and keeps the boundary.

## Legacy launchers

Devices below API 26 do not composite adaptive layers. Export flat PNGs from
`icon-512.svg` at 48, 72, 96, 144 and 192 px into the matching `mipmap-*dpi`
folders if you intend to support them. Android Studio's Image Asset tool will do
this from the same source.
