#include "VDM2Quant.h"
#include "VDM2Quant___024root.h"
#include "common.hpp"
#include <algorithm>
#include <cstdlib>
#include <filesystem>
#include <vector>

int main(int argc, char** argv) {
  try {
    Verilated::commandArgs(argc, argv);
    require(argc == 2, "usage: VDM2Quant <window_dir>");
    const std::filesystem::path window_dir = argv[1];
    const auto cfg = read_cfg(window_dir / "window.cfg");
    const auto ctx_in = read_words(window_dir / "artifacts" / "ctx_in.u32.bin", 26);
    const auto v_in = read_words(window_dir / "artifacts" / "v_in.u32.bin", 16);
    const auto golden = read_words(window_dir / "artifacts" / "golden.u32.bin", 16);
    const bool debug = std::getenv("DM2_DEBUG") != nullptr;
    int debug_left = 16;

    VDM2Quant dut;
    dut.io_cfg_prefill = 1;
    dut.io_cfg_seqlen = cfg_int(cfg, "cfg_seqlen");
    dut.io_cfg_single_query = 0;
    dut.io_cfg_valid = 0;
    dut.io_ctx_inv_scale = cfg_u32(cfg, "ctx_inv_scale_u32");
    dut.io_ctx_zero_point = cfg_u32(cfg, "ctx_zero_point_u8");
    dut.io_out_inv_scale = cfg_u32(cfg, "out_inv_scale_u32");
    dut.io_data_in_v_st = 0;
    dut.io_data_in_v_valid = 0;
    dut.io_data_in_v_last = 0;
    dut.io_data_in_ctx_st = 0;
    dut.io_data_in_ctx_valid = 0;
    dut.io_data_in_ctx_last = 0;
    dut.io_res_ready = 1;
    zero_words(dut.io_data_in_v, 16);
    zero_words(dut.io_data_in_ctx, 26);

    reset_dut(dut);

    for (std::size_t beat = 0; beat < v_in.beats(); ++beat) {
      copy_words(dut.io_data_in_v, v_in.beat(beat), 16);
      dut.io_data_in_v_addr = beat;
      dut.io_data_in_v_st = (beat == 0);
      dut.io_data_in_v_valid = 1;
      dut.io_data_in_v_last = (beat + 1 == v_in.beats());
      tick(dut);
    }
    dut.io_data_in_v_st = 0;
    dut.io_data_in_v_valid = 0;
    dut.io_data_in_v_last = 0;
    zero_words(dut.io_data_in_v, 16);

    for (std::size_t beat = 0; beat < ctx_in.beats(); ++beat) {
      copy_words(dut.io_data_in_ctx, ctx_in.beat(beat), 26);
      dut.io_data_in_ctx_addr = beat;
      dut.io_data_in_ctx_st = (beat == 0);
      dut.io_data_in_ctx_valid = 1;
      dut.io_data_in_ctx_last = (beat + 1 == ctx_in.beats());
      tick(dut);
    }
    dut.io_data_in_ctx_st = 0;
    dut.io_data_in_ctx_valid = 0;
    dut.io_data_in_ctx_last = 0;
    zero_words(dut.io_data_in_ctx, 26);

    dut.io_cfg_prefill = 1;
    dut.io_cfg_seqlen = cfg_int(cfg, "cfg_seqlen");
    dut.io_cfg_single_query = 0;
    dut.io_cfg_valid = 1;
    dut.io_ctx_inv_scale = cfg_u32(cfg, "ctx_inv_scale_u32");
    dut.io_ctx_zero_point = cfg_u32(cfg, "ctx_zero_point_u8");
    dut.io_out_inv_scale = cfg_u32(cfg, "out_inv_scale_u32");
    dut.io_res_ready = 1;
    tick(dut);
    dut.io_cfg_valid = 0;

    std::vector<uint32_t> observed(golden.words.size(), 0);
    std::vector<bool> seen(golden.beats(), false);
    bool saw_last = false;

    for (int cycle = 0; cycle < 200000 && std::find(seen.begin(), seen.end(), false) != seen.end(); ++cycle) {
      tick(dut);
      if (debug && debug_left > 0 &&
          (dut.rootp->DM2Quant__DOT__loaduInst__DOT__io_data_out_v_valid_REG ||
           dut.rootp->DM2Quant__DOT__loaduInst__DOT__io_data_out_ctx_valid_REG ||
           dut.io_res_valid)) {
        auto decode_lane = [](const WData* words, int lane) {
          const uint32_t word = words[lane / 4];
          uint32_t byte = (word >> (8 * (lane % 4))) & 0xffU;
          return static_cast<int>(static_cast<int8_t>(byte));
        };
        const auto decode_u8 = [](const WData* words, int lane) {
          const uint32_t word = words[lane / 4];
          return static_cast<int>((word >> (8 * (lane % 4))) & 0xffU);
        };
        std::cerr << "DM2 cycle"
                  << " v_valid=" << static_cast<int>(dut.rootp->DM2Quant__DOT__loaduInst__DOT__io_data_out_v_valid_REG)
                  << " ctx_valid=" << static_cast<int>(dut.rootp->DM2Quant__DOT__loaduInst__DOT__io_data_out_ctx_valid_REG)
                  << " res_valid=" << static_cast<int>(dut.io_res_valid)
                  << " loadu_state=" << static_cast<int>(dut.rootp->DM2Quant__DOT__loaduInst__DOT__st_state)
                  << " loadu_waitctx=" << static_cast<int>(dut.rootp->DM2Quant__DOT__loaduInst__DOT__waitctx_cnt_r)
                  << " loadu_prefillLoad=" << static_cast<int>(dut.rootp->DM2Quant__DOT__loaduInst__DOT__prefill_load_cnt_r)
                  << " dm_state=" << static_cast<int>(dut.rootp->DM2Quant__DOT__dmInst__DOT__state)
                  << " dm_waitctx=" << static_cast<int>(dut.rootp->DM2Quant__DOT__dmInst__DOT__waitctxCnt_r)
                  << " dm_mul=" << static_cast<int>(dut.rootp->DM2Quant__DOT__dmInst__DOT__mulCnt_r)
                  << " ctx0=" << decode_u8(dut.rootp->DM2Quant__DOT__dmInst__DOT__ctx, 0)
                  << " ctx1=" << decode_u8(dut.rootp->DM2Quant__DOT__dmInst__DOT__ctx, 1)
                  << " v00=" << decode_lane(dut.rootp->DM2Quant__DOT__dmInst__DOT__vBuf_0_r, 0)
                  << " v10=" << decode_lane(dut.rootp->DM2Quant__DOT__dmInst__DOT__vBuf_1_r, 0);
        if (dut.io_res_valid) {
          std::cerr << " out0=" << decode_lane(dut.io_res, 0)
                    << " gold0=" << decode_lane(golden.beat(dut.io_res_addr), 0)
                    << " addr=" << static_cast<int>(dut.io_res_addr);
        }
        std::cerr << std::endl;
        --debug_left;
      }
      if (!dut.io_res_valid) {
        continue;
      }
      const std::size_t addr = dut.io_res_addr;
      require(addr < golden.beats(), "DM2Quant output addr out of range");
      copy_words(observed.data() + addr * 16, dut.io_res, 16);
      seen[addr] = true;
      if (dut.io_res_last) {
        saw_last = true;
      }
    }

    require(std::find(seen.begin(), seen.end(), false) == seen.end(), "missing DM2Quant output beats");
    require(saw_last, "DM2Quant never asserted io_res_last");
    if (debug && golden.beats() > 0) {
      const auto obs = unpack_int8_lanes(observed.data(), 64);
      const auto exp = unpack_int8_lanes(golden.beat(0), 64);
      bool exact_same = true;
      std::size_t diff_lane = 0;
      int diff_obs = 0;
      int diff_exp = 0;
      for (std::size_t lane = 0; lane < 64; ++lane) {
        if (obs[lane] != exp[lane]) {
          exact_same = false;
          diff_lane = lane;
          diff_obs = obs[lane];
          diff_exp = exp[lane];
          break;
        }
      }
      if (exact_same) {
        std::cerr << "DM2Quant beat0 exact match" << std::endl;
      } else {
        std::cerr << "DM2Quant beat0 tolerated diff"
                  << " lane=" << diff_lane
                  << " observed=" << diff_obs
                  << " expected=" << diff_exp
                  << std::endl;
      }
    }
    for (std::size_t beat = 0; beat < golden.beats(); ++beat) {
      report_int8_mismatch("DM2Quant", beat, observed.data() + beat * 16, golden.beat(beat), 16, 64);
    }
    std::cout << "DM2Quant PASS beats=" << golden.beats() << std::endl;
    return 0;
  } catch (const std::exception& ex) {
    std::cerr << ex.what() << std::endl;
    return 1;
  }
}
