package main

import (
	"context"
	"github.com/Fanju6/NetProxy-Magisk/src/native/netproxy/internal/ebpf"
	"os"
)

func (c *cli) ebpf(ctx context.Context, args []string) error {
	action := "status"
	if len(args) > 0 {
		action = args[0]
		args = args[1:]
	}
	switch action {
	case "status":
		mode := "configured"
		raw := false
		for _, argument := range args {
			switch argument {
			case "configured", "all", "local", "shared":
				mode = argument
			case "--raw":
				raw = true
			default:
				return usageError("用法: netproxyctl ebpf status [configured|all|local|shared] [--raw]")
			}
		}
		options, err := ebpf.ResolveProbeOptions(c.options.EBPFConfig, mode)
		if err != nil {
			return &resultError{Code: "ebpf.status_failed", Message: err.Error()}
		}
		probeOutput, probeErr := ebpf.RunProbe(ctx, c.options.SingBoxPath, options)
		report, parseErr := ebpf.ParseProbeReport(probeOutput)
		if parseErr != nil {
			return &resultError{
				Code:    "ebpf.status_invalid",
				Message: parseErr.Error(),
				Data:    map[string]any{"raw": raw, "content": probeOutput},
			}
		}
		content := probeOutput
		if !raw {
			content = ebpf.FormatProbeOutput(report, probeErr)
		}
		data := map[string]any{
			"mode":    options.RequestedMode,
			"raw":     raw,
			"content": content,
			"report":  report,
		}
		if probeErr != nil {
			return &resultError{Code: "ebpf.unsupported", Message: "eBPF 能力检查未通过", Data: data}
		}
		writeJSON(os.Stdout, result{Schema: 1, OK: true, Code: "ebpf.status", Message: "eBPF 能力检查完成", Data: data})
		return nil
	default:
		return usageError("用法: netproxyctl ebpf status [configured|all|local|shared] [--raw]")
	}
}
