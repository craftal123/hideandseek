# HideAndSeek (Paper plugin)

This plugin implements your hide-and-seek format with GUI menus:

- 1 hider vs many hunters
- Shared minimap menu for readiness, questions, and powerups
- Hider presses **I'm Ready!** to lock position and start seeker phase
- Hunters ask categorized questions via GUI
- Hider chooses powerups manually (not auto-assigned)
- Positional answers darken eliminated map/world areas

## Prerequisites

- Java 21+
- Gradle 8+
- Paper 1.20.6 compatible server

## Build

```bash
gradle clean build
```

Jar output:

```bash
build/libs/hideandseek-0.1.0.jar
```

## Local dev runner

```bash
bash scripts/run-local-paper.sh
```

## Commands

```bash
/hns start <hider> <hunter1,hunter2,...> [hideSeconds]
/hns open
/hns minimap
/hns ask [questionId]
/hns complete
/hns disguise <block>
/hns status
/hns found <hiderName>
/hns stop
```

## GUI Flow

1. Start game with `/hns start ...`
2. Hider + hunters use `/hns open` (or right-click the menu compass)
3. Hider hides, then clicks **I'm Ready!**
4. Hunters open **Questions** menu and choose question icons (with hover tooltips)
5. Hider receives powerup picks and chooses from **Pick Powerups** menu
6. Click the minimap icon to open a dedicated minimap GUI, then click **Use This Minimap** to equip a live map in-hand
7. Minimap now uses a live block-color sampling renderer (map-like shading/depth) and darkens masked regions on the map
8. Eliminated regions are darkened in-world using client-side blacked-out terrain overlay for masked sides/areas

## Textures

Current icons are placeholders from vanilla materials so you can replace textures later with your pack.
