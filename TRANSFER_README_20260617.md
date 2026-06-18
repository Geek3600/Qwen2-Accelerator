# Qwen2-Accelerator Transfer Notes - 2026-06-17

This archive is for moving the active project context to another machine for
Codex recovery and on-site demo support.

Read these first after extraction:

1. `AGENTS.md`
2. `docs/context_2026-03-27.md`
3. `scripts/board/run_pyjm_office2010_multi_card.sh`
4. `scripts/board/run_pyjm_office2010_single_card.sh`
5. `scripts/board/deploy/README_DEPLOY.md` if present in a remote runtime package,
   otherwise use `scripts/board/deploy/init_node.sh` and `preflight_check.sh`.

Current on-site goal:

The backend only needs to run the full PYJM + Office2010 flow and update the
frontend status JSON in real time. Mathematical equivalence to the original OPT
sampling path is not required for this demo.

Main remote runtime path:

```bash
cd /home/hyyuan/pyjm12_demo
PYJM_SUDO_PASSWORD=<sudo-password> CARD_START_SPREAD_SEC=2 ./run_pyjm_office2010_multi_card.sh \
  --cards 0,1,2,3,4,5,6,7 \
  --parallel \
  --total-generate 1 \
  --poll-timeout-ms 5000 \
  --status-refresh 0.2
```

Frontend status file:

```text
/home/share/app_status.json
```

Expected status behavior:

- running cards show `application = 应用2-4`
- running cards show `task = task-10-N`
- finished cards return to `application = idle` and `task = ""`

Exit codes:

- `0`: Office2010 found a password
- `4`: full flow completed normally but no hit
- anything else: real error; inspect `multi_card_logs/card_<id>.log`
