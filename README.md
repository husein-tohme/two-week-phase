# Two Week Phase

A Fabric mod for Minecraft: a library of hardcore worlds that you share with one friend. Every world in it belongs to both of you.

Download: https://modrinth.com/mod/two-week-phase-mod

## What it does

- A library panel on the right side of the title screen
- Make a new world, or add a copy of one of your singleplayer worlds (the original is not changed)
- Two owners per world. Nobody else can join
- Always hardcore, no cheats
- If you die you become a ghost (spectator) and your friend keeps going
- Both owners keep the world on their own PC, so either can play when the other is offline
- With the Essential mod installed, opening a world shares it and invites the other owner automatically
- Optional online saving to a WebDAV storage, with progress and a clear "finished" message

## Status

Beta. The library, adding a world from singleplayer and sharing through Essential have been used in a real game on 26.2. The two-player parts and online saving need more testing. Bug reports are welcome under Issues.

## Building

You need JDK 25 and Gradle 9.7 or newer.

```
gradle build
```

That builds for Minecraft 26.2. For the other versions:

```
gradle build -Pminecraft_version=26.3 -Pfabric_api_version=0.161.0+26.3 -Pminecraft_dep=~26.3 -Pmod_version=1.6.0+26.3 -Pcompat=26.3
gradle build -Pminecraft_version=26.1.2 -Pfabric_api_version=0.155.3+26.1.2 -Pminecraft_dep=~26.1 -Pmod_version=1.6.0+26.1 -Pcompat=26.1
```

The few calls that are named differently between game versions live in `src/compat/<version>/`.

## Licence

MIT. See [LICENSE](LICENSE).
