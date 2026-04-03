#include "VQKVLinear.h"
#include "VQKVLinear___024root.h"
#include "common.hpp"
#include <algorithm>
#include <cstdlib>
#include <filesystem>
#include <vector>

int main(int argc, char** argv) {
  try {
    Verilated::commandArgs(argc, argv);
    require(argc == 2, "usage: VQKVLinear <window_dir>");
    const std::filesystem::path window_dir = argv[1];
    const auto cfg = read_cfg(window_dir / "window.cfg");
    const auto data_in = read_words(window_dir / "artifacts" / "data_in.u32.bin", 3);
    const auto weight_init = read_words(window_dir / "artifacts" / "weight_init.u32.bin", 9);
    const auto bias_init = read_words(window_dir / "artifacts" / "bias_init.u32.bin", 3);
    const auto golden = read_words(window_dir / "artifacts" / "golden.u32.bin", 2);
    const auto v_chunk0_path = window_dir / "artifacts" / "v_chunk0.u32.bin";
    const bool has_v_chunk0 = std::filesystem::exists(v_chunk0_path);
    PackedWords v_chunk0;
    if (has_v_chunk0) {
      v_chunk0 = read_words(v_chunk0_path, 3);
    }
    const bool debug = std::getenv("QKV_DEBUG") != nullptr;
    int debug_input_left = 8;
    int debug_output_left = 8;

    VQKVLinear dut;
    dut.io_data_in_st = 0;
    dut.io_data_valid = 0;
    dut.io_data_last = 0;
    dut.io_layer_st = 0;
    dut.io_weight_init_mode = 0;
    dut.io_bias_init_valid = 0;
    dut.io_cfg_prefill = 1;
    dut.io_cfg_seqlen = cfg_int(cfg, "cfg_seqlen");
    dut.io_cfg_valid = 0;
    dut.io_data_out_ready = 1;
    dut.io_q_out_inv_scale = cfg_u32(cfg, "q_out_inv_scale_u32");
    dut.io_k_out_inv_scale = cfg_u32(cfg, "k_out_inv_scale_u32");
    dut.io_v_out_inv_scale = cfg_u32(cfg, "v_out_inv_scale_u32");
    dut.io_q_bias_scale = cfg_u32(cfg, "q_bias_scale_u32");
    dut.io_k_bias_scale = cfg_u32(cfg, "k_bias_scale_u32");
    dut.io_v_bias_scale = cfg_u32(cfg, "v_bias_scale_u32");
    zero_words(dut.io_data_in, 3);
    zero_words(dut.io_weight_init_data, 9);
    zero_words(dut.io_bias_init_data, 3);

    reset_dut(dut);

    std::vector<bool> seen_weight(weight_init.beats(), false);
    dut.io_weight_init_mode = 1;
    dut.io_layer_st = 1;
    for (int cycle = 0; cycle < static_cast<int>(weight_init.beats() * 4); ++cycle) {
      const std::size_t addr = dut.io_weight_init_addr;
      if (addr < weight_init.beats()) {
        copy_words(dut.io_weight_init_data, weight_init.beat(addr), 9);
        seen_weight[addr] = true;
      } else {
        zero_words(dut.io_weight_init_data, 9);
      }
      tick(dut);
      if (std::find(seen_weight.begin(), seen_weight.end(), false) == seen_weight.end()) {
        break;
      }
    }
    require(
        std::find(seen_weight.begin(), seen_weight.end(), false) == seen_weight.end(),
        "QKVLinear weight init did not cover all addresses");
    dut.io_weight_init_mode = 0;
    dut.io_layer_st = 0;
    zero_words(dut.io_weight_init_data, 9);
    tick(dut);

    reset_dut(dut);
    dut.io_layer_st = 0;
    dut.io_weight_init_mode = 0;
    dut.io_bias_init_valid = 0;
    dut.io_cfg_prefill = 1;
    dut.io_cfg_seqlen = cfg_int(cfg, "cfg_seqlen");
    dut.io_cfg_valid = 0;
    dut.io_data_out_ready = 0;
    dut.io_q_out_inv_scale = cfg_u32(cfg, "q_out_inv_scale_u32");
    dut.io_k_out_inv_scale = cfg_u32(cfg, "k_out_inv_scale_u32");
    dut.io_v_out_inv_scale = cfg_u32(cfg, "v_out_inv_scale_u32");
    dut.io_q_bias_scale = cfg_u32(cfg, "q_bias_scale_u32");
    dut.io_k_bias_scale = cfg_u32(cfg, "k_bias_scale_u32");
    dut.io_v_bias_scale = cfg_u32(cfg, "v_bias_scale_u32");
    dut.io_data_in_st = 0;
    dut.io_data_valid = 0;
    dut.io_data_last = 0;
    zero_words(dut.io_data_in, 3);
    zero_words(dut.io_weight_init_data, 9);
    zero_words(dut.io_bias_init_data, 3);

    for (std::size_t beat = 0; beat < bias_init.beats(); ++beat) {
      copy_words(dut.io_bias_init_data, bias_init.beat(beat), 3);
      dut.io_bias_init_valid = 1;
      tick(dut);
    }
    dut.io_bias_init_valid = 0;
    zero_words(dut.io_bias_init_data, 3);

    for (std::size_t beat = 0; beat < data_in.beats(); ++beat) {
      copy_words(dut.io_data_in, data_in.beat(beat), 3);
      dut.io_data_addr = beat;
      dut.io_data_in_st = (beat == 0);
      dut.io_data_valid = 1;
      dut.io_data_last = (beat + 1 == data_in.beats());
      tick(dut);
    }
    dut.io_data_in_st = 0;
    dut.io_data_valid = 0;
    dut.io_data_last = 0;
    zero_words(dut.io_data_in, 3);

    dut.io_layer_st = 1;
    tick(dut);
    dut.io_layer_st = 0;
    for (int cycle = 0; cycle < 28; ++cycle) {
      tick(dut);
    }

    dut.io_cfg_prefill = 1;
    dut.io_cfg_seqlen = cfg_int(cfg, "cfg_seqlen");
    dut.io_q_out_inv_scale = cfg_u32(cfg, "q_out_inv_scale_u32");
    dut.io_k_out_inv_scale = cfg_u32(cfg, "k_out_inv_scale_u32");
    dut.io_v_out_inv_scale = cfg_u32(cfg, "v_out_inv_scale_u32");
    dut.io_q_bias_scale = cfg_u32(cfg, "q_bias_scale_u32");
    dut.io_k_bias_scale = cfg_u32(cfg, "k_bias_scale_u32");
    dut.io_v_bias_scale = cfg_u32(cfg, "v_bias_scale_u32");
    dut.io_cfg_valid = 1;
    dut.io_data_out_ready = 1;
    tick(dut);
    dut.io_cfg_valid = 0;

    const std::size_t token_count = static_cast<std::size_t>(cfg_int(cfg, "token_count"));
    const std::size_t beats_per_token = 32;
    const std::size_t beats_per_head = token_count * beats_per_token;
    std::size_t seen_beats = 0;
    bool saw_st = false;
    int last_count = 0;
    bool reported_tolerated_exact = false;
    bool reported_qkv_internal[3] = {false, false, false};

    for (int cycle = 0; cycle < 200000 && seen_beats < golden.beats(); ++cycle) {
      tick(dut);
      if (debug && dut.rootp->QKVLinear__DOT__su_inst__DOT__io_data_out_valid_REG) {
        const uint32_t addr = dut.rootp->QKVLinear__DOT__su_inst__DOT__io_data_out_addr_REG;
        int probe_idx = -1;
        if (addr == 0) {
          probe_idx = 0;
        } else if (addr == 64) {
          probe_idx = 1;
        } else if (addr == 128) {
          probe_idx = 2;
        }
        if (probe_idx >= 0 && !reported_qkv_internal[probe_idx]) {
          const auto* raw_words = &dut.rootp->QKVLinear__DOT__su_inst__DOT__io_data_out_REG[0];
          const auto* scaled_words = &dut.rootp->QKVLinear__DOT___epilogue_inst_io_out[0];
          const uint32_t* bias_words = nullptr;
          switch (addr) {
            case 0:
              bias_words = &dut.rootp->QKVLinear__DOT__bias_mem_0[0];
              break;
            case 64:
              bias_words = &dut.rootp->QKVLinear__DOT__bias_mem_64[0];
              break;
            case 128:
              bias_words = &dut.rootp->QKVLinear__DOT__bias_mem_128[0];
              break;
            default:
              break;
          }
          std::cerr << "QKV internal addr=" << addr
                    << " raw=" << hex_words(raw_words, 12)
                    << " scaled=" << hex_words(scaled_words, 12);
          if (bias_words != nullptr) {
            std::cerr << " bias=" << hex_words(bias_words, 3);
          }
          std::cerr << std::endl;
          reported_qkv_internal[probe_idx] = true;
        }
      }
      if (debug && has_v_chunk0 && dut.rootp->QKVLinear__DOT__state == 2 && debug_input_left > 0) {
        const auto obs = unpack_int8_lanes(dut.rootp->QKVLinear__DOT__vec_buffer_0_128, 12);
        const auto exp = unpack_int8_lanes(v_chunk0.beat(0), 12);
        bool exact_same = true;
        std::size_t diff_lane = 0;
        int diff_obs = 0;
        int diff_exp = 0;
        for (std::size_t lane = 0; lane < 12; ++lane) {
          if (obs[lane] != exp[lane]) {
            exact_same = false;
            diff_lane = lane;
            diff_obs = obs[lane];
            diff_exp = exp[lane];
            break;
          }
        }
        if (exact_same) {
          std::cerr << "QKV vec_buffer v0 exact match" << std::endl;
        } else {
          std::cerr << "QKV vec_buffer v0 tolerated diff"
                    << " lane=" << diff_lane
                    << " observed=" << diff_obs
                    << " expected=" << diff_exp
                    << std::endl;
        }
        --debug_input_left;
      }
      if (debug && debug_input_left > 0 && dut.rootp->QKVLinear__DOT__lu_inst__DOT__io_data_out_valid_REG) {
        const auto* pipe_words =
            &dut.rootp->QKVLinear__DOT__cu_inst__DOT__indata_pipreg__DOT__pipData_2_REG[0];
        std::cerr << "QKV input sample=" << (8 - debug_input_left)
                  << " lu_block=" << static_cast<int>(dut.rootp->QKVLinear__DOT__lu_inst__DOT__block_cnt_r)
                  << " lu_addr=" << dut.io_data_addr
                  << " pipe2=" << hex_words(pipe_words, 3)
                  << " w_lane0=" << static_cast<int>(
                         static_cast<int8_t>(dut.rootp->QKVLinear__DOT__cu_inst__DOT____Vcellinp__mul_list_0_0__io_in1))
                  << " w_r_sel=" << static_cast<int>(dut.rootp->QKVLinear__DOT__cu_inst__DOT__weight_r_sel)
                  << " w_w_sel=" << static_cast<int>(dut.rootp->QKVLinear__DOT__cu_inst__DOT__weight_w_sel)
                  << " cu_block=" << static_cast<int>(dut.rootp->QKVLinear__DOT__cu_inst__DOT__block_cnt_r)
                  << " lw_rowblock=" << static_cast<int>(dut.rootp->QKVLinear__DOT__lw_inst__DOT__rowblock_cnt_r)
                  << " lw_colblock=" << static_cast<int>(dut.rootp->QKVLinear__DOT__lw_inst__DOT__colblock_cnt_r)
                  << std::endl;
        --debug_input_left;
      }
      if (!dut.io_data_out_valid) {
        continue;
      }
      require(seen_beats < golden.beats(), "QKVLinear produced too many output beats");
      uint32_t observed_words[2] = {
          static_cast<uint32_t>(dut.io_data_out & 0xffffffffULL),
          static_cast<uint32_t>((dut.io_data_out >> 32) & 0xffffULL),
      };
      if (debug && debug_output_left > 0) {
        std::cerr << "QKV output beat=" << seen_beats
                  << " out=" << hex_words(observed_words, 2)
                  << " golden=" << hex_words(golden.beat(seen_beats), 2)
                  << " head_cnt=" << static_cast<int>(dut.rootp->QKVLinear__DOT__head_cnt_r)
                  << " output_cnt=" << static_cast<int>(dut.rootp->QKVLinear__DOT__output_cnt_r)
                  << " w_r_sel=" << static_cast<int>(dut.rootp->QKVLinear__DOT__cu_inst__DOT__weight_r_sel)
                  << " w_w_sel=" << static_cast<int>(dut.rootp->QKVLinear__DOT__cu_inst__DOT__weight_w_sel)
                  << std::endl;
        --debug_output_left;
      }
      report_int8_mismatch("QKVLinear", seen_beats, observed_words, golden.beat(seen_beats), 2, 6);
      if (debug && !reported_tolerated_exact) {
        const auto obs = unpack_int8_lanes(observed_words, 6);
        const auto exp = unpack_int8_lanes(golden.beat(seen_beats), 6);
        for (std::size_t lane = 0; lane < 6; ++lane) {
          if (obs[lane] != exp[lane]) {
            std::cerr << "QKVLinear tolerated diff"
                      << " beat=" << seen_beats
                      << " lane=" << lane
                      << " observed=" << obs[lane]
                      << " expected=" << exp[lane]
                      << std::endl;
            reported_tolerated_exact = true;
            break;
          }
        }
      }

      const std::size_t beat_in_head = seen_beats % beats_per_head;
      const std::size_t expected_addr = beat_in_head;
      require(
          dut.io_data_out_addr == expected_addr,
          "QKVLinear output addr mismatch at sequence beat=" + std::to_string(seen_beats));

      const bool expected_last = (beat_in_head + 1 == beats_per_head);
      require(
          static_cast<bool>(dut.io_data_out_last) == expected_last,
          "QKVLinear output last mismatch at sequence beat=" + std::to_string(seen_beats));

      const bool expected_st = (beat_in_head == 0);
      require(
          static_cast<bool>(dut.io_data_out_st) == expected_st,
          "QKVLinear output st mismatch at sequence beat=" + std::to_string(seen_beats));

      saw_st = saw_st || dut.io_data_out_st;
      last_count += dut.io_data_out_last ? 1 : 0;
      ++seen_beats;
    }

    require(
        seen_beats == golden.beats(),
        "missing QKVLinear output beats observed=" + std::to_string(seen_beats) +
            " expected=" + std::to_string(golden.beats()));
    require(saw_st, "QKVLinear never asserted io_data_out_st");
    require(last_count == 12, "QKVLinear unexpected io_data_out_last count");
    std::cout << "QKVLinear PASS beats=" << seen_beats << std::endl;
    return 0;
  } catch (const std::exception& ex) {
    std::cerr << ex.what() << std::endl;
    return 1;
  }
}
