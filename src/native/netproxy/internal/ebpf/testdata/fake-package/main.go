package main

import (
	"os"
	"time"
)

func main() {
	if err := os.WriteFile(os.Getenv("NETPROXY_PACKAGE_STARTED"), []byte("started"), 0o600); err != nil {
		panic(err)
	}
	time.Sleep(time.Minute)
}
