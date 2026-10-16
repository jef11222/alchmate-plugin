# AlchMate (RuneLite Plugin)

**AlchMate** is an in-game Old School RuneScape companion plugin that tracks High Alchemy sessions, calculates exact profit margins with dynamic cost-basis tracking, projects your progress toward buying an Old School Bond, and serves an embedded local web dashboard directly from your RuneLite client.

---

## Features

- **Live Session Tracking:** Tracks every High Alchemy cast, XP earned, nature runes consumed, and real-time net profit.
- **Dynamic Cost-Basis Detection:** Automatically differentiates whether an alched item was acquired via Grand Exchange (`[GE]`) or peer-to-peer / W308 Anvil bulk trading (`[P2P]`), adjusting your realized margins accurately.
- **Bond Roadmap & Wealth HUD:** Displays your total wealth (including cash in bank, inventory, and coins tied up in active Grand Exchange buy slots) against the current Bond price.
- **Customizable In-Game Overlay:** Clean on-screen overlay showing current session stats, cast counts, and hourly profit rates.
- **Side Panel Integration:** In-game sidebar panel with quick status, one-click dashboard launcher, and bug reporting.
- **100% Pure Local Web Dashboard:** Serves an interactive, cross-device Next.js web application directly from RuneLite at `http://127.0.0.1:18833` with zero external cloud dependencies and zero latency.

---

## Privacy & Safety

- **100% Local & Private:** In strict compliance with RuneLite Plugin Hub rules, the plugin never sends player telemetry, account credentials, or passwords to third-party or remote servers. All web serving and multi-account data aggregation runs strictly on your local machine (`127.0.0.1:18833`).
- **Safe:** AlchMate strictly complies with Jagex's third-party client rules. No macros, automated actions, or unauthorized inputs are performed.

---

## License

This project is licensed under the BSD 3-Clause License. See the [LICENSE](LICENSE) file for details.
