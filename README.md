# ml7 Bot

Helper Bot for mL7 channel stuff.

## Features

- **Incredibly minimalistic and unused Modmail thingy**

- **Automated Command Changelog.** Detects Nightbot Command Changes through twitch chat or the Dashboard and pushes a message to a discord channel.


## Use

1. **Build:** `./gradlew build`

2. **Config:** Copy `config.example.properties` to `config.properties` and adjust as needed
    * To get the Discord Id's, enable Developer mode in settings, right click channel > Copy ID

3. **Run:** `java -jar build/libs/ml7bot-SNAPSHOT-all.jar config.properties`


## Use with Docker Setup

`docker-compose up -d`

* Prometheus is running on port `9090`
