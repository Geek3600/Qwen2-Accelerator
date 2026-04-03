#include "VSoftmaxPipFP32.h"
#include "VSoftmaxPipFP32___024root.h"
#include "common.hpp"
#include <algorithm>
#include <cstdlib>
#include <filesystem>
#include <vector>

int main(int argc, char** argv) {
  try {
    Verilated::commandArgs(argc, argv);
    require(argc == 2, "usage: VSoftmaxPipFP32 <window_dir>");
    const std::filesystem::path window_dir = argv[1];
    const auto cfg = read_cfg(window_dir / "window.cfg");
    const auto data_in = read_words(window_dir / "artifacts" / "data_in.u32.bin", 26);
    const auto masks = read_words(window_dir / "artifacts" / "masks.u32.bin", 1);
    const auto golden = read_words(window_dir / "artifacts" / "golden.u32.bin", 26);
    const bool debug = std::getenv("SOFTMAX_DEBUG") != nullptr;
    const int layer_delay = std::getenv("SOFTMAX_LAYER_DELAY") ? std::atoi(std::getenv("SOFTMAX_LAYER_DELAY")) : 0;
    int debug_left = 8;

    VSoftmaxPipFP32 dut;
    dut.io_layer_st = 0;
    dut.io_data_in_st = 0;
    dut.io_data_valid = 0;
    dut.io_data_last = 0;
    dut.io_cfg_prefill = 1;
    dut.io_cfg_seqlen = cfg_int(cfg, "cfg_seqlen");
    dut.io_cfg_single_query = 0;
    dut.io_cfg_valid = 1;
    dut.io_res_ready = 1;
    zero_words(dut.io_data_in, 26);
    dut.io_w_in = 0;

    reset_dut(dut);

    for (std::size_t beat = 0; beat < data_in.beats(); ++beat) {
      copy_words(dut.io_data_in, data_in.beat(beat), 26);
      dut.io_data_addr = beat;
      dut.io_data_in_st = (beat == 0);
      dut.io_data_valid = 1;
      dut.io_data_last = (beat + 1 == data_in.beats());
      tick(dut);
    }
    dut.io_data_in_st = 0;
    dut.io_data_valid = 0;
    dut.io_data_last = 0;
    zero_words(dut.io_data_in, 26);

    for (int i = 0; i < layer_delay; ++i) {
      if (dut.io_w_addr < masks.beats()) {
        dut.io_w_in = masks.beat(dut.io_w_addr)[0];
      } else {
        dut.io_w_in = 0;
      }
      tick(dut);
    }

    dut.io_layer_st = 1;
    if (dut.io_w_addr < masks.beats()) {
      dut.io_w_in = masks.beat(dut.io_w_addr)[0];
    } else {
      dut.io_w_in = 0;
    }
    tick(dut);
    dut.io_layer_st = 0;

    std::vector<uint32_t> observed(golden.words.size(), 0);
    std::vector<bool> seen(golden.beats(), false);
    bool saw_last = false;

    for (int cycle = 0; cycle < 200000 && std::find(seen.begin(), seen.end(), false) != seen.end(); ++cycle) {
      if (dut.io_w_addr < masks.beats()) {
        dut.io_w_in = masks.beat(dut.io_w_addr)[0];
      } else {
        dut.io_w_in = 0;
      }
      tick(dut);
      if (!dut.io_res_valid) {
        continue;
      }
      const std::size_t addr = dut.io_res_addr;
      require(addr < golden.beats(), "SoftmaxPipFP32 output addr out of range");
      copy_words(observed.data() + addr * 26, dut.io_res, 26);
      seen[addr] = true;
      if (debug && debug_left > 0) {
        std::string mask_bits;
        mask_bits.reserve(26);
        const auto push_mask = [&mask_bits](CData bit) {
          mask_bits.push_back(bit ? '1' : '0');
        };
        push_mask(dut.rootp->SoftmaxPipFP32__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_25);
        push_mask(dut.rootp->SoftmaxPipFP32__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_24);
        push_mask(dut.rootp->SoftmaxPipFP32__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_23);
        push_mask(dut.rootp->SoftmaxPipFP32__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_22);
        push_mask(dut.rootp->SoftmaxPipFP32__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_21);
        push_mask(dut.rootp->SoftmaxPipFP32__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_20);
        push_mask(dut.rootp->SoftmaxPipFP32__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_19);
        push_mask(dut.rootp->SoftmaxPipFP32__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_18);
        push_mask(dut.rootp->SoftmaxPipFP32__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_17);
        push_mask(dut.rootp->SoftmaxPipFP32__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_16);
        push_mask(dut.rootp->SoftmaxPipFP32__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_15);
        push_mask(dut.rootp->SoftmaxPipFP32__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_14);
        push_mask(dut.rootp->SoftmaxPipFP32__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_13);
        push_mask(dut.rootp->SoftmaxPipFP32__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_12);
        push_mask(dut.rootp->SoftmaxPipFP32__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_11);
        push_mask(dut.rootp->SoftmaxPipFP32__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_10);
        push_mask(dut.rootp->SoftmaxPipFP32__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_9);
        push_mask(dut.rootp->SoftmaxPipFP32__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_8);
        push_mask(dut.rootp->SoftmaxPipFP32__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_7);
        push_mask(dut.rootp->SoftmaxPipFP32__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_6);
        push_mask(dut.rootp->SoftmaxPipFP32__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_5);
        push_mask(dut.rootp->SoftmaxPipFP32__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_4);
        push_mask(dut.rootp->SoftmaxPipFP32__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_3);
        push_mask(dut.rootp->SoftmaxPipFP32__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_2);
        push_mask(dut.rootp->SoftmaxPipFP32__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_1);
        push_mask(dut.rootp->SoftmaxPipFP32__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_0);
        std::cerr << "Softmax output addr=" << addr
                  << " last=" << static_cast<int>(dut.io_res_last)
                  << " st=" << static_cast<int>(dut.io_res_st)
                  << " w_addr=" << dut.io_w_addr
                  << " lw_valid=" << static_cast<int>(dut.rootp->SoftmaxPipFP32__DOT__lwInst__DOT__io_data_out_valid_REG)
                  << " lu_valid=" << static_cast<int>(dut.rootp->SoftmaxPipFP32__DOT__luInst__DOT__io_data_out_valid_REG)
                  << " prefill_cnt=" << static_cast<int>(dut.rootp->SoftmaxPipFP32__DOT__lwInst__DOT__prefill_batchsize_cnt_r)
                  << " mask_bits=" << mask_bits
                  << std::endl;
        --debug_left;
      }
      if (dut.io_res_last) {
        saw_last = true;
      }
    }

    require(
        std::find(seen.begin(), seen.end(), false) == seen.end(),
        "missing SoftmaxPipFP32 output beats");
    require(saw_last, "SoftmaxPipFP32 never asserted io_res_last");
    for (std::size_t beat = 0; beat < golden.beats(); ++beat) {
      report_fp32_mismatch("SoftmaxPipFP32", beat, observed.data() + beat * 26, golden.beat(beat), 26);
    }
    std::cout << "SoftmaxPipFP32 PASS beats=" << golden.beats() << std::endl;
    return 0;
  } catch (const std::exception& ex) {
    std::cerr << ex.what() << std::endl;
    return 1;
  }
}
