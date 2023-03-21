![Discord](https://img.shields.io/discord/1083519371068047390)
[![Github All Releases](https://img.shields.io/github/downloads/L0615T1C5-216AC-9437/LogicUpdater/total.svg)]()
# Logic Updater
A mindustry plugin that checks if logic block code has a newer version. Attem is updated into supply crew.

 **ATTENTION:**
1) We need developers to submit logic code in the [LU](https://discord.gg/rtC4mmdWZa) server. The more submissions we get, the more old code gets updated.
2) The api uses HTTP, not HTTPS, please refrain from attempting to access the api with https requests.

## Installation Guide
1. Download the latest mod verion in [#Releases](https://github.com/L0615T1C5-216AC-9437/LogicUpdater/releases).  
2. Go to your server's directory \ config \ mods
3. Move the mod (`Jar` file) into the mods folder  
4. Restart the server.  
5. Use the `mods` command to list all mods. If you see GIB as a mod, GIB was successfully installed.  
6. Join the LU discord server and use the bot to receive an API key.  
7. Config the api key through the `luconfig` command.  
8. Restart the server once again and look for a successful api connection message.  

## Usage
The plugin will scan the code of logic blocks, only when placed, for `drawflush` which signifies the code prints to a screen.  
The code is then hashed and sent to `http://c-n.ddns.net:8888` to see if the hash is banned.  

## Settings  
These are the raw setting names for the `luconfig`.
* `ApiKey` (String): Api Key to access LU API. To get an API key go to discord.gg/v7SyYd2D3y and use the bot slash command.
* `ConnectionTimeout` (Int): How long, in millis, the server will wait for a http response before giving up.  
default: `1000`  
*Note: `c-n.ddns.net` does not respond to pings.*   
* `HTTPThreadCount` (Int): Max # of threads to use for HTTP requests.  
default: `4`  
* `CacheTTL` (Int): How many minutes the cache will retain data for.  
default: `5`  

## Commands  
`luconfig`: Same as `config` but for GIB settings. Set value to `default` for default value.  
`luclearcache`: Clears the hash cache

## RPC Info
Rate Limit: Depends on API tier, unverified users can check 10 hashes per second.
