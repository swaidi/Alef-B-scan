# Alef_B_scan

A private, offline replacement for "Circle to Search" text copying on GrapheneOS.
Long-press a thin strip at the bottom edge of the screen, then tap or circle any text
(in a chat, web page, app or image) and copy it.

- Text shown by apps (chats, web pages, documents) is read directly, so it is exact in Arabic and English.
- Text inside images is recognized on the phone with Tesseract (high-accuracy Arabic and English); selected image text is re-read closely before copying.
- No Google services. The internet is used only by the Update button (check and download new versions); screen text is never sent.
- Updates: the in-app Update button (dot when a new version exists), or Obtainium.
- English and Arabic interface; copy history kept only on the phone; test report for troubleshooting.

## Selecting text

- **Tap a word** to select it, then drag the two handles to extend the selection.
- **Circle text** to select every word the circle touches (whole words).
- The glowing bar under the selection offers **Copy**, **Share**, **Sentence → Paragraph**
  (grows the selection step by step) and **All** (every word on the screen). On narrow screens or with large fonts the bar shows icons only; long-press an icon to see its name.

## Shortcuts (⚡ button)

Tap ⚡ (always the last button on the right) to run the pinned shortcut. Hold ⚡ for the full list;
hold a shortcut in that list to Pin, Edit or Remove it. Shortcuts are also managed in the app's settings.

- **Smart action** (pinned at first): phone numbers (Call / WhatsApp), emails, links, addresses, dates (the phone's own
  calendar action), IBANs (checksum-verified) and reference codes such as HMG-SW-070-26.
- **Translate**: Arabic ↔ English with the phone's built-in on-device translation when available; otherwise your
  translator app, or a prompt to install one.
- **App text action**: sends the text to another app's text action (Translate, AI assistant, dictionary).
- **Share directly** to a chosen app.
- **Links**: Search Google, Search IMDb, or a custom link with `{text}`.
- **Clean up & copy**: numbers only, emails & phone numbers only, join lines, or copy with a template.

Link and app shortcuts hand the text to the chosen app.

## Triggers

| Trigger | Needs accessibility service | Notes |
|---|---|---|
| Bottom-edge long-press | Yes | Closest to the Motorola gesture. Size and position are adjustable. |
| Quick toggle tile | — | Turns the service off in one tap (e.g. before banking apps). |
| Capture tile (fallback) | No | Uses screen capture; Android asks for consent each time — choose **Entire screen**. |

## First-time setup on the phone

1. Install the APK from the latest release (or through Obtainium).
2. Open **Settings → Apps → Alef_B_scan → ⋮ → Allow restricted settings**.
3. Open **Settings → Accessibility → Alef_B_scan trigger** and turn it on.
4. In the app, turn on **Show strip outline** and adjust the strip so it sits just above the
   navigation handle, then turn the outline off.
5. Optional: add the tiles from the Quick Settings edit screen.

## Obtainium (updates)

Add app → App source URL: `https://github.com/swaidi/Alef-B-scan` → Add.

## Build

Every push to `main` runs `.github/workflows/release.yml`, which downloads the OCR models,
builds a signed arm64 APK and publishes a release tagged `v1.0.<run number>`.
Builds started on other branches produce a test APK only (no release).

Required repository secrets: `KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`,
`SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`. Keep the keystore safe: losing it means
future updates cannot be installed over the existing app.

## License

Copyright © 2026 Swaidi (trading as A.T.Grep).

Free for **individuals** for personal, non-commercial use. Use by any **organization** (company,
small business, government body, charity or school), commercial use, distributing changed versions
and reusing the code need written approval and may involve fees. See [LICENSE.md](LICENSE.md).
To ask, open an [issue](https://github.com/swaidi/Alef-B-scan/issues) or email dev.oasis006@passmail.net.
