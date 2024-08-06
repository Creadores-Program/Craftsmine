# CraftsMine (WIP)
Welcome to this project that has done the impossible 😉! Imagine a server compatible with Minecraft Pocket or as some call it, Craftsman with Minecraft Bedrock!

Many told me it was impossible, but you know me, so I accepted the challenge and here I am!

**THIS IS THE FIRST SOFTWARE THAT ALLOWS MINECRAFT POCKET (0.15.x) PLAYERS TO JOIN MINECRAFT BEDROCK (1.7.0) SERVERS!!**

## Requirements:
- Java 8 or higher installed.
- Maven installed.
- Internet connection.
- The port you will use to open the server must be open.

## What is this??...
This is **SOFTWARE**. It is not a **plugin** for any Minecraft server software.

## How to Get It?
Simply join our [Discord](https://discord.com/invite/mrmHcwxXff) and get it at an **affordable** and **negotiable** price! Don't wait any longer!

## How Does It Work?
This proxy translates Minecraft Pocket datapacks to Minecraft Bedrock and vice versa, something like this:

MCPE -> CraftsMine Proxy Translator -> Minecraft Bedrock Server.

Minecraft Bedrock Server -> CraftsMine Proxy Translator -> MCPE

**Remember that this software sends the Minecraft Pocket player as Minecraft Bedrock 1.7!**

## What Works?
Here is a list of things that work in this **Software**!

**----------------------------------**

✔️ **Works Well**

❕ **Works with Bugs**

❌ **Does Not Work**

**----------------------------------**

  - ✔️ Sounds
  - ✔️ Server Datapackets Login (Handshake)
  - ✔️ Skin
  - ✔️ Chat
  - ✔️ Commands
  - ✔️ Player Spawn
  - ✔️ Time
  - ✔️ Weather
  - ✔️ Game mode change (Survival, Creative, Adventure, and Spectator)
  - ❕ Position Effect
  - ❕ Animations
  - ❕ Particles
  - ❕ Playerlist
  - ❕ Player Interaction
  - ❕ Player Health
  - ❕ Player Hunger
  - ❕ Respawn
  - ❕ Multi World
  - ❌ Xbox auth
  - ❌ Terrain Generation
  - ❌ Movement
  - ❌ Entities (including players)
  - ❌ Inventory
  - ❌ Levels
  - ❌ UI/Forms

  And more...

## Tested MCPE Versions:

 - MCPE 0.15.10
 - MCPE 0.15.9

## How to Install:
Simply place the jar file as the program initializer, for example, in a Pterodactyl panel, just put it in the main directory and rename it to **server.jar** or whatever the jar file that **starts the server** is called! Remember that the program is written in **Java**!

To allow Minecraft Pocket players to join with Minecraft Bedrock 1.21 or the current version, you can use [Nukkit PetteriM1 Edition](https://github.com/PetteriM1/NukkitPetteriM1Edition/)

## Configuration:
The configuration is in the main directory of the server, it is the config.yml file (remember to turn the server on and off to generate the configuration!)

#### bindAddress
This is the IP where the CraftsMine server opens (most of the time it is not necessary to modify it).

#### port
This is the port where the CraftsMine server opens.

#### motd
This is the message of the day that the CraftsMine server will show.

#### submotd
This is the sub-message of the day that appears below the motd when CraftsMine is in LAN.

#### maxplayers
This is the maximum number of players that the CraftsMine server accepts.

#### shutdownMessage
This is the message that shows when the proxy is shut down.

#### bedrockAddress
This is the IP of the destination Minecraft Bedrock server.

#### bedrockPort
This is the port of the destination Minecraft Bedrock server.

#### debug
If enabled, it sends debug messages to the console.

#### gcCollectionDiconnectPlayer
If enabled, it frees memory every time a player disconnects from the server.

## Support the Project

If you want to support the project, you can make a donation at this [link](https://creadoresgames.blogspot.com/p/donaciones.html) via bank transfer or PayPal!
Thank you very much ☺️

## Credits:

  - [Barrel CREA Edition](https://github.com/Trollhunters501/Barrel-CREA-Edition)
  - [MBNukkit](https://github.com/Trollhunters501/MBNukkit)
  - [DragonProxy](https://github.com/robske110/DragonProxy/)
  - [Protocol Cloudburstmc](https://github.com/CloudburstMC/Protocol/)
