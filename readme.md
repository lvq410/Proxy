纯基于Java8的代理服务。支持的代理方式/协议有：纯tcp反向代理，http代理协议，socks5代理协议，内网穿透，私有WebSocket协议。

* [tcp反向代理](docs/tcp.md)
* [socks5](docs/socks5.md)
* [http](docs/http.md)
* [内网穿透](docs/intranet.md)
* [私有WebSocket](docs/pws.md)

# 起因
笔者曾有需要搭建一套代理服务。因为这玩意儿网上按说已经烂大街了的，所以笔者一开始想直接用网上的各种已有实现，如[v2ray](https://github.com/233boy/v2ray)、[goproxy](https://github.com/snail007/goproxy)等等。

调研了一圈后发现，这类代理服务，部署的时候都有一个要求：需要机器root权限。

很不幸，笔者没有机器root权限（啥奇葩场景你想，你仔细想……）

最后死活找不到不需要root权限也能部署的，一怒之下，干脆自己研究这些代理协议，自己撸一个，就有了本项目

# 特性
* 有java8+环境就行，无需root权限
* 基于java nio，性能肯定干不过那些个基于c的，go的之类的。不过代理嘛，都是用来干坏事的，需要高性能的话，本项目就不适合你了

# 部署
## 机器环境
1. 确保机器环境有java8+
1. [Release](https://github.com/lvq410/Proxy/releases)里下载zip包，解压
1. 根据需要调整config/application.yml配置，以及start.sh里的java命令位置等
1. 执行start.sh即可

## docker
1. 镜像为 `lvq410/proxy:{version}` [hub.docker](https://hub.docker.com/repository/docker/lvq410/proxy)
1. 可参考[Release](https://github.com/lvq410/Proxy/releases)包里的config/application.yml配置文件，通过调整环境变量来调整配置