#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REMOTE_HOST="${REMOTE_HOST:-hyyuan@10.12.133.23}"
REMOTE_PORT="${REMOTE_PORT:-22}"
REMOTE_PROJECT="${REMOTE_PROJECT:-/home/hyyuan/workspace/v6.0_9p_cnn_2slr_4core_yolov8/v6.0_9p_cnn_v0825/app_shell_9p.xpr}"
REMOTE_TCL="${REMOTE_TCL:-/tmp/fix_opt_acc_core_ooc_run.tcl}"
REMOTE_FP_IP_GEN_TCL="${REMOTE_FP_IP_GEN_TCL:-/tmp/gen_xilinx_fp_ips_23.tcl}"
REMOTE_IP_REPO_DIR="${REMOTE_IP_REPO_DIR:-/home/hyyuan/workspace/opt_acc}"
REMOTE_FP_IP_DIR="${REMOTE_FP_IP_DIR:-$REMOTE_IP_REPO_DIR/fp_ip}"
REMOTE_FP_IP_GEN_PROJECT="${REMOTE_FP_IP_GEN_PROJECT:-/tmp/fp_ip_gen_proj}"
REMOTE_PROJECT_DIR="${REMOTE_PROJECT_DIR:-$(dirname "$REMOTE_PROJECT")}"
REMOTE_OPTACC_XDC="${REMOTE_OPTACC_XDC:-$REMOTE_PROJECT_DIR/app_shell_9p.srcs/constrs_1/new/opt_acc_core_resources.xdc}"
REMOTE_IPSHARED_GLOB="${REMOTE_IPSHARED_GLOB:-$REMOTE_PROJECT_DIR/app_shell_9p.gen/sources_1/bd/app_shell_9p/ipshared/*}"
REMOTE_IPUSER_IPSHARED_GLOB="${REMOTE_IPUSER_IPSHARED_GLOB:-$REMOTE_PROJECT_DIR/app_shell_9p.ip_user_files/bd/app_shell_9p/ipshared/*}"
VIVADO_BIN="${VIVADO_BIN:-/home/EDA/Xilinx/Vivado/2021.1/bin/vivado}"
MODE="${1:-scripts_only}"
JOBS="${2:-16}"

LOCAL_TCL="$ROOT_DIR/scripts/synthesis/fix_opt_acc_core_ooc_run.tcl"
LOCAL_FP_IP_GEN_TCL="$ROOT_DIR/scripts/synthesis/gen_xilinx_fp_ips_23.tcl"
LOCAL_OPT_CORE="$ROOT_DIR/deliverables/vivado_opt_acc_core_ip/hdl/opt_acc_core.sv"
LOCAL_TOP_VIVADO="$ROOT_DIR/deliverables/vivado_opt_acc_core_ip/hdl/Top_vivado.sv"
LOCAL_OPTACC_XDC="$ROOT_DIR/constraints/opt_acc_core_resources.xdc"

case "$MODE" in
  prepare_only|scripts_only|launch|launch_wait)
    ;;
  *)
    echo "Usage: $0 [prepare_only|scripts_only|launch|launch_wait] [jobs]" >&2
    exit 1
    ;;
esac

for f in "$LOCAL_TCL" "$LOCAL_FP_IP_GEN_TCL" "$LOCAL_OPT_CORE" "$LOCAL_TOP_VIVADO" "$LOCAL_OPTACC_XDC"; do
  if [[ ! -f "$f" ]]; then
    echo "Missing required file: $f" >&2
    exit 1
  fi
done

ssh -p "$REMOTE_PORT" \
  -o StrictHostKeyChecking=no \
  -o UserKnownHostsFile=/tmp/codex_ssh_known_hosts \
  "$REMOTE_HOST" \
  "mkdir -p $REMOTE_IP_REPO_DIR $REMOTE_FP_IP_DIR $(dirname "$REMOTE_OPTACC_XDC")"

scp -q -P "$REMOTE_PORT" \
  -o StrictHostKeyChecking=no \
  -o UserKnownHostsFile=/tmp/codex_ssh_known_hosts \
  "$LOCAL_OPT_CORE" "$REMOTE_HOST:$REMOTE_IP_REPO_DIR/opt_acc_core.sv"

scp -q -P "$REMOTE_PORT" \
  -o StrictHostKeyChecking=no \
  -o UserKnownHostsFile=/tmp/codex_ssh_known_hosts \
  "$LOCAL_TOP_VIVADO" "$REMOTE_HOST:$REMOTE_IP_REPO_DIR/Top_vivado.sv"

scp -q -P "$REMOTE_PORT" \
  -o StrictHostKeyChecking=no \
  -o UserKnownHostsFile=/tmp/codex_ssh_known_hosts \
  "$LOCAL_OPTACC_XDC" "$REMOTE_HOST:$REMOTE_OPTACC_XDC"

scp -q -P "$REMOTE_PORT" \
  -o StrictHostKeyChecking=no \
  -o UserKnownHostsFile=/tmp/codex_ssh_known_hosts \
  "$LOCAL_TCL" "$REMOTE_HOST:$REMOTE_TCL"

scp -q -P "$REMOTE_PORT" \
  -o StrictHostKeyChecking=no \
  -o UserKnownHostsFile=/tmp/codex_ssh_known_hosts \
  "$LOCAL_FP_IP_GEN_TCL" "$REMOTE_HOST:$REMOTE_FP_IP_GEN_TCL"

ssh -p "$REMOTE_PORT" \
  -o StrictHostKeyChecking=no \
  -o UserKnownHostsFile=/tmp/codex_ssh_known_hosts \
  "$REMOTE_HOST" \
  "set -e; for d in $REMOTE_IPSHARED_GLOB; do \
     if [ -f \"\$d/opt_acc_core.sv\" ]; then cp $REMOTE_IP_REPO_DIR/opt_acc_core.sv \"\$d/opt_acc_core.sv\"; fi; \
     if [ -f \"\$d/Top_vivado.sv\" ]; then cp $REMOTE_IP_REPO_DIR/Top_vivado.sv \"\$d/Top_vivado.sv\"; fi; \
   done; \
   for d in $REMOTE_IPUSER_IPSHARED_GLOB; do \
     if [ -f \"\$d/opt_acc_core.sv\" ]; then cp $REMOTE_IP_REPO_DIR/opt_acc_core.sv \"\$d/opt_acc_core.sv\"; fi; \
     if [ -f \"\$d/Top_vivado.sv\" ]; then cp $REMOTE_IP_REPO_DIR/Top_vivado.sv \"\$d/Top_vivado.sv\"; fi; \
   done"

ssh -p "$REMOTE_PORT" \
  -o StrictHostKeyChecking=no \
  -o UserKnownHostsFile=/tmp/codex_ssh_known_hosts \
  "$REMOTE_HOST" \
  "$VIVADO_BIN -mode batch -source $REMOTE_FP_IP_GEN_TCL -tclargs $REMOTE_FP_IP_GEN_PROJECT $REMOTE_FP_IP_DIR xcvu9p_CIV-flgb2104-2-i"

ssh -p "$REMOTE_PORT" \
  -o StrictHostKeyChecking=no \
  -o UserKnownHostsFile=/tmp/codex_ssh_known_hosts \
  "$REMOTE_HOST" \
  "$VIVADO_BIN -mode batch -source $REMOTE_TCL -tclargs $REMOTE_PROJECT app_shell_9p_opt_acc_core_0_3 app_shell_9p_opt_acc_core_0_3_synth_1 $MODE $JOBS"
