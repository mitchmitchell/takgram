# TAKgram

A bridge between TAK (ATAK, WinTAK, iTAK) and Telegram.

- Shows TAK objects in a Telegram channel as readable cards: icon on a map thumbnail, plain-language type,
  latitude/longitude, MGRS grid, map links and a native Telegram pin. A reader needs no TAK access to find the object.
- Needs **no ATAK plugin**, so nothing has to be signed. The bridge is an ordinary program that speaks Cursor on Target.
- Works **without a TAK Server**: it joins the standard TAK mesh multicast groups and also offers a small built-in
  endpoint that TAK clients add like a server.

**Status: 0.1, first release in progress.** The unit tests pass, but this version has not yet been run against a real
ATAK device or a live Telegram chat. See [What is not verified yet](#what-is-not-verified-yet).

## How it works

```
ATAK / WinTAK / iTAK  <-- mesh multicast or built-in endpoint -->  TAKgram  <-- Bot API -->  Telegram
```

| TAK event | Telegram result |
| --- | --- |
| Marker, alert, CASEVAC, shape, route | One photo card in the display channel, edited in place when the object changes |
| Delete, or stale time passed | The card is marked REMOVED or STALE |
| Chat to All Chat Rooms | Text in the operations group |
| A user's position | Nothing, until that user turns sharing on from inside TAK; then one live-location message and a roster line |

Positions are private by default. The bridge appears in every TAK contact list as **TAKgram**; send it a direct chat
message:

| Message | Effect |
| --- | --- |
| `share on` | Your position is published to Telegram |
| `share off` | Publishing stops |
| `status` | Shows your sharing state |

In Telegram: `/roster`, `/where NAME`, `/id`, `/help`. Each card has **Pin** (sends a native location you can
navigate to) and **Open in TAK** (sends the object as a data package). Text typed in the operations group by an
allowlisted user is sent into TAK chat.

## Quick start

1. Create a bot with [@BotFather](https://t.me/BotFather) and keep the token.
2. Create a channel for cards and a group for chat. Add the bot to both; make it an administrator of the channel.
3. Install and write a config:

   ```sh
   pip install .
   cp takgram.example.toml takgram.toml
   export TAKGRAM_TOKEN=123456:ABC...
   ```

4. Start the bridge once with `display_chat` left out (setup mode: nothing is published), send `/id` in each chat,
   and put the ids into `takgram.toml`. Add your own user id to `allow_users`. The token can also live in a file
   outside the repo: set `telegram.token_file` and make the file readable only by you.
5. Run it:

   ```sh
   takgram run -c takgram.toml
   ```

6. Connect TAK clients, either way:
   - **Same Wi-Fi or LAN:** nothing to do; ATAK's default mesh networking reaches the bridge.
   - **Any network that can reach the bridge host:** make a connection package and import it on each device.

     ```sh
     takgram datapackage --host 192.168.1.10 --port 8087
     ```

To preview a card without Telegram: `takgram card some-event.cot -o card.png`.

## Phase 0 checks

Three questions have to be answered on real devices and bots before the next phases. Each has a script in `spikes/`:

| Script | Question |
| --- | --- |
| `bot_to_bot.py` | Does one bot receive another bot's posts in a channel, and in a group? Decides how bridges exchange CoT. |
| `edit_rate.py` | How fast can messages and live locations be edited before Telegram rate-limits? Sets the intervals. |
| `send_datapackages.py` | Do ATAK and iTAK import a connection package and a marker package opened from Telegram? |

## What is not verified yet

- The data package formats and the GeoChat direct-message format are written from the known file layouts and have not
  been imported on a device.
- The mesh attachment reads XML CoT only. ATAK switches a mesh to TAK protobuf only when every peer advertises it, and
  the bridge does not, so a standard mesh should stay on XML. This needs confirming on a real network.
- The built-in endpoint is plain TCP with no TLS and no authentication. Run it on a trusted network or VPN only.
- Icons: TAK users get their team-colored circle and custom iconset files are honored if you place the iconset folders
  in `icons_dir`. MIL-STD-2525 symbols are drawn as the correct frame shape and color with a short label, not the full
  symbol. Full symbols load from `icons_dir/2525/<cot-type>.png` if you supply them; generating that set is planned.
- Map tiles come from the public OpenStreetMap tile server by default and are cached. Heavy use should point
  `tile_url` at your own tile source.

## Security notes

- Telegram bot chats are not end-to-end encrypted. Do not put anything on the display channel that Telegram or the
  channel's members should not see.
- Only users in `allow_users`, writing in `ops_chat`, can send anything into TAK. Messages from other bots are ignored.
- The bot token is read from `TAKGRAM_TOKEN` or a token file, never from the config file.

## Roadmap

1. Phase 1 (this release): serverless display, sharing control from inside TAK.
2. Phase 2: Telegram users share positions and place markers (`/share`, `/mark`, `/sos`).
3. Phase 3: encrypted CoT trunk between bridges over a Telegram channel; TAK Server client attachment.
4. Phase 4: shape and route detail, attachments, a JSON contract for other bots, Termux packaging.

## Development

```sh
pip install -e .[dev]
pytest
```

## License

Apache-2.0. Map data © OpenStreetMap contributors.
