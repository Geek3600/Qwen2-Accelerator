#include "VDM1FP32.h"
#include "common.hpp"
#include <algorithm>
#include <cstdlib>
#include <filesystem>
#include <vector>

int main(int argc, char** argv) {
  try {
    Verilated::commandArgs(argc, argv);
    require(argc == 2, "usage: VDM1FP32 <window_dir>");
    const std::filesystem::path window_dir = argv[1];
    const auto cfg = read_cfg(window_dir / "window.cfg");
    const auto data_in = read_words(window_dir / "artifacts" / "data_in.u32.bin", 1);
    const auto golden = read_words(window_dir / "artifacts" / "golden.u32.bin", 26);
    const bool debug = std::getenv("DM1_DEBUG") != nullptr;
    int debug_left = 8;

    VDM1FP32 dut;
    dut.io_data_in_st = 0;
    dut.io_data_valid = 0;
    dut.io_data_last = 0;
    dut.io_cfg_prefill = 1;
    dut.io_cfg_seqlen = cfg_int(cfg, "cfg_seqlen");
    dut.io_cfg_single_query = 0;
    dut.io_cfg_valid = 0;
    dut.io_res_ready = 1;
    dut.io_out_scale = cfg_u32(cfg, "out_scale_u32");
    dut.io_data_in = 0;
    dut.io_data_addr = 0;

    reset_dut(dut);

    for (std::size_t beat = 0; beat < data_in.beats(); ++beat) {
      dut.io_data_in = data_in.beat(beat)[0];
      dut.io_data_addr = beat;
      dut.io_data_in_st = (beat == 0);
      dut.io_data_valid = 1;
      dut.io_data_last = (beat + 1 == data_in.beats());
      tick(dut);
    }
    dut.io_data_in_st = 0;
    dut.io_data_valid = 0;
    dut.io_data_last = 0;
    dut.io_data_in = 0;
    tick(dut);

    dut.io_cfg_prefill = 1;
    dut.io_cfg_seqlen = cfg_int(cfg, "cfg_seqlen");
    dut.io_cfg_single_query = 0;
    dut.io_out_scale = cfg_u32(cfg, "out_scale_u32");
    dut.io_cfg_valid = 1;
    dut.io_res_ready = 1;
    tick(dut);
    dut.io_cfg_valid = 0;

    std::vector<uint32_t> observed(golden.words.size(), 0);
    std::vector<bool> seen(golden.beats(), false);
    bool saw_last = false;

    for (int cycle = 0; cycle < 200000 && std::find(seen.begin(), seen.end(), false) != seen.end(); ++cycle) {
      tick(dut);
      if (!dut.io_res_valid) {
        continue;
      }
      const std::size_t addr = dut.io_res_addr;
      require(addr < golden.beats(), "DM1FP32 output addr out of range");
      copy_words(observed.data() + addr * 26, dut.io_res, 26);
      seen[addr] = true;
      if (debug && debug_left > 0) {
        std::cerr << "DM1 output addr=" << addr
                  << " valid=" << static_cast<int>(dut.io_res_valid)
                  << " last=" << static_cast<int>(dut.io_res_last)
                  << " st=" << static_cast<int>(dut.io_res_st)
                  << std::endl;
        --debug_left;
      }
      if (dut.io_res_last) {
        saw_last = true;
      }
    }

    require(std::find(seen.begin(), seen.end(), false) == seen.end(), "missing DM1FP32 output beats");
    require(saw_last, "DM1FP32 never asserted io_res_last");
    for (std::size_t beat = 0; beat < golden.beats(); ++beat) {
      report_fp32_mismatch("DM1FP32", beat, observed.data() + beat * 26, golden.beat(beat), 26);
    }
    std::cout << "DM1FP32 PASS beats=" << golden.beats() << std::endl;
    return 0;
  } catch (const std::exception& ex) {
    std::cerr << ex.what() << std::endl;
    return 1;
  }
}
