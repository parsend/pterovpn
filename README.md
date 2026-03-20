# ptera vpn

# Мобильная версия в разработке.
#  Проект был написан unitdev - telegram: tcptransit

License: MIT © unitdev (parsend)

## server (java)

`config.properties` рядом с jar.

```
listenPorts=25565
token=change-me
udpChannels=4
```

запуск:

```bash
java -jar ptera-vpn-server.jar
```

## client (go, linux / windows)

Linux:

```bash
sudo ./ptera-client \
  --server 1.2.3.4:25565 \
  --token change-me \
  --tun ptera0 \
  --tun-cidr 10.13.37.2/24 \
  --mtu 1420
```

multiport (выключено по умолчанию):

```bash
sudo ./ptera-client \
  --server 1.2.3.4 \
  --ports 25565,25566,25567 \
  --token change-me
```

split tunnel — в туннель только указанные сети (по умолчанию все = default):

```bash
sudo ./ptera-client --server 1.2.3.4:25565 --token x \
  --routes 0.0.0.0/0,::/0
```

только одна сеть:

```bash
sudo ./ptera-client --server 1.2.3.4:25565 --token x \
  --routes 1.2.3.0/24
```

exclude — не пускать в туннель локальные подсети (идёт через обычный шлюз):

```bash
sudo ./ptera-client --server 1.2.3.4:25565 --token x \
  --exclude 192.168.0.0/16,10.0.0.0/8
```

Windows (запуск от администратора, нужен wintun.dll рядом с exe — в релизах уже есть, иначе https://www.wintun.net):

```bash
ptera-client.exe --server 1.2.3.4:25565 --token change-me
```

TUI (linux / windows):

```bash
./ptera-client --tui
```

Конфиги в `~/.config/pteravpn/` (linux) или `%APPDATA%\pteravpn\` (windows). Формат JSON:

```json
{"server": "1.2.3.4:25565", "token": "secret", "routes": "", "exclude": "192.168.0.0/16"}
```

