# ptera vpn

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

## client (go, linux)

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

### Конфиг клиента

Файл в порядке поиска: `--config`, `./ptera-client.conf`, `~/.config/pteravpn/client.conf`. 

Ключи: `server`, `ports`, `servers` (резерв через запятую), `token`, `tun`, `tunCIDR`, `mtu`, `keepaliveSec`, `reconnect`, `includeRoutes`, `excludeRoutes`, `quiet`, `obfuscate`, `compression`.


