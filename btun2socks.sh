#!/bin/zsh
set -x
cd tun2socks
TUN2SOCKS_DIR="${0:a:h}"
cd $TUN2SOCKS_DIR
go get
go install golang.org/x/mobile/cmd/gomobile@latest
go get golang.org/x/mobile/bind
go get
make
gomobile init
gomobile bind  -ldflags="-s -w" -o ../android/app/libs/tun2socks.aar -target android -androidapi 28 ./engine
ls -lh ../android/app/libs/tun2socks.aar