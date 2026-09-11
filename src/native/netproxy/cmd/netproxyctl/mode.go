package main

import (
	"context"
	"os"

	moduleconfig "github.com/Fanju6/NetProxy-Magisk/src/native/netproxy/internal/config"
	moduleapp "github.com/Fanju6/NetProxy-Magisk/src/native/netproxy/internal/module"
)

func (c *cli) mode(ctx context.Context, args []string) error {
	positionals := args
	if len(positionals) == 0 {
		options := c.options
		module, err := moduleconfig.LoadModule(options.ModuleConfig)
		if err != nil {
			return err
		}
		writeJSON(os.Stdout, result{Schema: 1, OK: true, Code: "mode.current", Message: "当前出站模式", Data: map[string]any{"mode": module.OutboundMode, "available": []string{"rule", "global", "direct", "AllowAds"}}})
		return nil
	}
	if err := moduleapp.ApplyMode(ctx, c.options, positionals[0]); err != nil {
		return err
	}
	writeJSON(os.Stdout, result{Schema: 1, OK: true, Code: "mode.changed", Message: "出站模式已切换", Data: map[string]string{"mode": positionals[0]}})
	return nil
}
