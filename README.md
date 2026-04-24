# HideAndSeek (Paper plugin)

This plugin implements the hide-and-seek format from your transcript:

- 1 hider vs many hunters
- Hunters ask location questions
- Every answered question gives the hider a sabotage bonus and gives hunters a mandatory challenge
- Hunters dying adds time to the hider
- Hider can disguise as any block

## Prerequisites

- Java 21+
- Gradle 8+
- A Paper 1.20.6 server

## Build

```bash
gradle clean build
```

The plugin jar is produced at:

```bash
build/libs/hideandseek-0.1.0.jar
```

## Run locally (added to repo)

A helper script is included at:

```bash
scripts/run-local-paper.sh
```

It will:

1. Create `local-paper-server/`
2. Download Paper 1.20.6 build 151 (if missing)
3. Build this plugin
4. Copy the plugin jar into `local-paper-server/plugins/`
5. Accept EULA for local development
6. Start the server

Run it with:

```bash
bash scripts/run-local-paper.sh
```

## Commands

### Start a round

```bash
/hns start <hider> <hunter1,hunter2,...> [hideSeconds]
```

### Ask questions

```bash
/hns ask northSouth
/hns ask eastWest
/hns ask sameBiome
/hns ask aboveY75
/hns ask distance250
/hns ask waterOrLava
```

### Complete generated challenge

```bash
/hns complete
```

### Hider disguise

```bash
/hns disguise stone
```

### End on find

```bash
/hns found <hiderName>
```

## Notes

- This is a gameplay framework and can be extended with custom challenges/sabotages.
- True skin morphing is not directly provided by Bukkit/Paper API, so this plugin uses invisibility + block-helmet disguise.
