#include "VTop.h"
#include "VTop_Fp32Add.h"
#include "VTop_WeightMem_2.h"
#include "VTop___024root.h"
#include "common.hpp"
#include <algorithm>
#include <cstdlib>
#include <filesystem>
#include <iomanip>
#include <sstream>
#include <string>
#include <vector>

namespace {

void drive_from_ddr(
    VTop& dut,
    const PackedWords& data_in,
    const PackedWords& qkv_weight_init,
    const PackedWords& sm_masks,
    const PackedWords& out_weight_init,
    const PackedWords& ffnup_weight_init,
    const PackedWords& ffndown_weight_init) {
  if (dut.io_data_in_addr < data_in.beats()) {
    copy_words(dut.io_data_in, data_in.beat(dut.io_data_in_addr), 12);
  } else {
    zero_words(dut.io_data_in, 12);
  }

  if (dut.io_qkv_w_addr < qkv_weight_init.beats()) {
    copy_words(dut.io_qkv_w_in, qkv_weight_init.beat(dut.io_qkv_w_addr), 9);
  } else {
    zero_words(dut.io_qkv_w_in, 9);
  }

  dut.io_sm_w_in = dut.io_sm_w_addr < sm_masks.beats() ? sm_masks.beat(dut.io_sm_w_addr)[0] : 0U;

  if (dut.io_out_w_addr < out_weight_init.beats()) {
    copy_words(dut.io_out_w_in, out_weight_init.beat(dut.io_out_w_addr), 9);
  } else {
    zero_words(dut.io_out_w_in, 9);
  }

  if (dut.io_ffnup_w_addr < ffnup_weight_init.beats()) {
    copy_words(dut.io_ffnup_w_in, ffnup_weight_init.beat(dut.io_ffnup_w_addr), 9);
  } else {
    zero_words(dut.io_ffnup_w_in, 9);
  }

  if (dut.io_ffndown_w_addr < ffndown_weight_init.beats()) {
    copy_words(dut.io_ffndown_w_in, ffndown_weight_init.beat(dut.io_ffndown_w_addr), 9);
  } else {
    zero_words(dut.io_ffndown_w_in, 9);
  }
}

template <typename Dut>
void tick_with_ddr(
    Dut& dut,
    const PackedWords& data_in,
    const PackedWords& qkv_weight_init,
    const PackedWords& sm_masks,
    const PackedWords& out_weight_init,
    const PackedWords& ffnup_weight_init,
    const PackedWords& ffndown_weight_init) {
  drive_from_ddr(dut, data_in, qkv_weight_init, sm_masks, out_weight_init, ffnup_weight_init, ffndown_weight_init);
  tick(dut);
}

template <typename Dut>
void reset_with_ddr(
    Dut& dut,
    const PackedWords& data_in,
    const PackedWords& qkv_weight_init,
    const PackedWords& sm_masks,
    const PackedWords& out_weight_init,
    const PackedWords& ffnup_weight_init,
    const PackedWords& ffndown_weight_init,
    int cycles = 5) {
  dut.reset = 1;
  for (int idx = 0; idx < cycles; ++idx) {
    tick_with_ddr(dut, data_in, qkv_weight_init, sm_masks, out_weight_init, ffnup_weight_init, ffndown_weight_init);
  }
  dut.reset = 0;
  tick_with_ddr(dut, data_in, qkv_weight_init, sm_masks, out_weight_init, ffnup_weight_init, ffndown_weight_init);
}

template <typename Dut>
void stream_words(
    Dut& dut,
    uint32_t* port,
    CData& valid,
    const PackedWords& data,
    std::size_t words_per_beat,
    const PackedWords& data_in,
    const PackedWords& qkv_weight_init,
    const PackedWords& sm_masks,
    const PackedWords& out_weight_init,
    const PackedWords& ffnup_weight_init,
    const PackedWords& ffndown_weight_init) {
  for (std::size_t beat = 0; beat < data.beats(); ++beat) {
    copy_words(port, data.beat(beat), words_per_beat);
    valid = 1;
    tick_with_ddr(dut, data_in, qkv_weight_init, sm_masks, out_weight_init, ffnup_weight_init, ffndown_weight_init);
  }
  valid = 0;
  zero_words(port, words_per_beat);
}

bool all_seen(const std::vector<bool>& seen) {
  return std::find(seen.begin(), seen.end(), false) == seen.end();
}

int decode_i8_lane(const WData* words, int lane);

std::string fp32_prefix(const uint32_t* words, std::size_t lanes, std::size_t count) {
  std::ostringstream oss;
  oss << std::fixed << std::setprecision(6);
  const auto limit = std::min(lanes, count);
  for (std::size_t i = 0; i < limit; ++i) {
    if (i) {
      oss << ",";
    }
    oss << u32_to_float(words[i]);
  }
  return oss.str();
}

std::string i8_prefix(const uint32_t* words, std::size_t lanes) {
  std::ostringstream oss;
  for (std::size_t i = 0; i < lanes; ++i) {
    if (i) {
      oss << ",";
    }
    oss << decode_i8_lane(words, static_cast<int>(i));
  }
  return oss.str();
}

int decode_i8_lane(const WData* words, int lane) {
  const uint32_t word = words[lane / 4];
  const uint32_t byte = (word >> (8 * (lane % 4))) & 0xffU;
  return static_cast<int>(static_cast<int8_t>(byte));
}

int decode_u8_lane(const WData* words, int lane) {
  const uint32_t word = words[lane / 4];
  return static_cast<int>((word >> (8 * (lane % 4))) & 0xffU);
}

using Mem72 = VlUnpacked<VlWide<3>, 4096>;

std::array<const Mem72*, 68> ffndown_weight_mem_views(const VTop& dut) {
  const auto* mem = dut.rootp->__PVT__Top__DOT__ffndown__DOT__lw_inst__DOT__weight_mem;
  return {
      &mem->__PVT__weight_banks_0_0_ext__DOT__Memory, &mem->__PVT__weight_banks_0_1_ext__DOT__Memory,
      &mem->__PVT__weight_banks_0_2_ext__DOT__Memory, &mem->__PVT__weight_banks_0_3_ext__DOT__Memory,
      &mem->__PVT__weight_banks_1_0_ext__DOT__Memory, &mem->__PVT__weight_banks_1_1_ext__DOT__Memory,
      &mem->__PVT__weight_banks_1_2_ext__DOT__Memory, &mem->__PVT__weight_banks_1_3_ext__DOT__Memory,
      &mem->__PVT__weight_banks_2_0_ext__DOT__Memory, &mem->__PVT__weight_banks_2_1_ext__DOT__Memory,
      &mem->__PVT__weight_banks_2_2_ext__DOT__Memory, &mem->__PVT__weight_banks_2_3_ext__DOT__Memory,
      &mem->__PVT__weight_banks_3_0_ext__DOT__Memory, &mem->__PVT__weight_banks_3_1_ext__DOT__Memory,
      &mem->__PVT__weight_banks_3_2_ext__DOT__Memory, &mem->__PVT__weight_banks_3_3_ext__DOT__Memory,
      &mem->__PVT__weight_banks_4_0_ext__DOT__Memory, &mem->__PVT__weight_banks_4_1_ext__DOT__Memory,
      &mem->__PVT__weight_banks_4_2_ext__DOT__Memory, &mem->__PVT__weight_banks_4_3_ext__DOT__Memory,
      &mem->__PVT__weight_banks_5_0_ext__DOT__Memory, &mem->__PVT__weight_banks_5_1_ext__DOT__Memory,
      &mem->__PVT__weight_banks_5_2_ext__DOT__Memory, &mem->__PVT__weight_banks_5_3_ext__DOT__Memory,
      &mem->__PVT__weight_banks_6_0_ext__DOT__Memory, &mem->__PVT__weight_banks_6_1_ext__DOT__Memory,
      &mem->__PVT__weight_banks_6_2_ext__DOT__Memory, &mem->__PVT__weight_banks_6_3_ext__DOT__Memory,
      &mem->__PVT__weight_banks_7_0_ext__DOT__Memory, &mem->__PVT__weight_banks_7_1_ext__DOT__Memory,
      &mem->__PVT__weight_banks_7_2_ext__DOT__Memory, &mem->__PVT__weight_banks_7_3_ext__DOT__Memory,
      &mem->__PVT__weight_banks_8_0_ext__DOT__Memory, &mem->__PVT__weight_banks_8_1_ext__DOT__Memory,
      &mem->__PVT__weight_banks_8_2_ext__DOT__Memory, &mem->__PVT__weight_banks_8_3_ext__DOT__Memory,
      &mem->__PVT__weight_banks_9_0_ext__DOT__Memory, &mem->__PVT__weight_banks_9_1_ext__DOT__Memory,
      &mem->__PVT__weight_banks_9_2_ext__DOT__Memory, &mem->__PVT__weight_banks_9_3_ext__DOT__Memory,
      &mem->__PVT__weight_banks_10_0_ext__DOT__Memory, &mem->__PVT__weight_banks_10_1_ext__DOT__Memory,
      &mem->__PVT__weight_banks_10_2_ext__DOT__Memory, &mem->__PVT__weight_banks_10_3_ext__DOT__Memory,
      &mem->__PVT__weight_banks_11_0_ext__DOT__Memory, &mem->__PVT__weight_banks_11_1_ext__DOT__Memory,
      &mem->__PVT__weight_banks_11_2_ext__DOT__Memory, &mem->__PVT__weight_banks_11_3_ext__DOT__Memory,
      &mem->__PVT__weight_banks_12_0_ext__DOT__Memory, &mem->__PVT__weight_banks_12_1_ext__DOT__Memory,
      &mem->__PVT__weight_banks_12_2_ext__DOT__Memory, &mem->__PVT__weight_banks_12_3_ext__DOT__Memory,
      &mem->__PVT__weight_banks_13_0_ext__DOT__Memory, &mem->__PVT__weight_banks_13_1_ext__DOT__Memory,
      &mem->__PVT__weight_banks_13_2_ext__DOT__Memory, &mem->__PVT__weight_banks_13_3_ext__DOT__Memory,
      &mem->__PVT__weight_banks_14_0_ext__DOT__Memory, &mem->__PVT__weight_banks_14_1_ext__DOT__Memory,
      &mem->__PVT__weight_banks_14_2_ext__DOT__Memory, &mem->__PVT__weight_banks_14_3_ext__DOT__Memory,
      &mem->__PVT__weight_banks_15_0_ext__DOT__Memory, &mem->__PVT__weight_banks_15_1_ext__DOT__Memory,
      &mem->__PVT__weight_banks_15_2_ext__DOT__Memory, &mem->__PVT__weight_banks_15_3_ext__DOT__Memory,
      &mem->__PVT__weight_banks_16_0_ext__DOT__Memory, &mem->__PVT__weight_banks_16_1_ext__DOT__Memory,
      &mem->__PVT__weight_banks_16_2_ext__DOT__Memory, &mem->__PVT__weight_banks_16_3_ext__DOT__Memory};
}

std::array<uint32_t, 3> expected_slice_words(const uint32_t* beat, std::size_t slice_idx) {
  std::array<uint8_t, 36> bytes{};
  for (std::size_t word_idx = 0; word_idx < 9; ++word_idx) {
    const uint32_t word = beat[word_idx];
    bytes[word_idx * 4 + 0] = static_cast<uint8_t>(word & 0xffU);
    bytes[word_idx * 4 + 1] = static_cast<uint8_t>((word >> 8) & 0xffU);
    bytes[word_idx * 4 + 2] = static_cast<uint8_t>((word >> 16) & 0xffU);
    bytes[word_idx * 4 + 3] = static_cast<uint8_t>((word >> 24) & 0xffU);
  }
  const std::size_t byte_base = slice_idx * 9;
  return {
      static_cast<uint32_t>(bytes[byte_base + 0]) |
          (static_cast<uint32_t>(bytes[byte_base + 1]) << 8) |
          (static_cast<uint32_t>(bytes[byte_base + 2]) << 16) |
          (static_cast<uint32_t>(bytes[byte_base + 3]) << 24),
      static_cast<uint32_t>(bytes[byte_base + 4]) |
          (static_cast<uint32_t>(bytes[byte_base + 5]) << 8) |
          (static_cast<uint32_t>(bytes[byte_base + 6]) << 16) |
          (static_cast<uint32_t>(bytes[byte_base + 7]) << 24),
      static_cast<uint32_t>(bytes[byte_base + 8]),
  };
}

std::array<uint32_t, 3> observed_slice_words(const VlWide<3>& slice_word) {
  return {slice_word[0], slice_word[1], slice_word[2] & 0xffU};
}

template <std::size_t N>
std::array<uint32_t, N> read_wide(const VlWide<N>& wide) {
  std::array<uint32_t, N> out{};
  for (std::size_t idx = 0; idx < out.size(); ++idx) {
    out[idx] = wide[idx];
  }
  return out;
}

template <std::size_t N>
std::size_t find_exact_beat(const PackedWords& packed, const std::array<uint32_t, N>& observed) {
  for (std::size_t beat = 0; beat < packed.beats(); ++beat) {
    bool match = true;
    for (std::size_t word = 0; word < observed.size(); ++word) {
      if (packed.beat(beat)[word] != observed[word]) {
        match = false;
        break;
      }
    }
    if (match) {
      return beat;
    }
  }
  return static_cast<std::size_t>(-1);
}

template <std::size_t N>
std::size_t find_exact_beat_words(const PackedWords& packed, const VlWide<N>& observed_wide) {
  return find_exact_beat(packed, read_wide(observed_wide));
}

void probe_ffndown_weight_memory(const VTop& dut, const PackedWords& weight_init) {
  const auto memories = ffndown_weight_mem_views(dut);
  for (std::size_t addr = 0; addr < weight_init.beats(); ++addr) {
    const std::size_t bank = addr >> 12;
    const std::size_t bank_addr = addr & 0xfffU;
    for (std::size_t slice = 0; slice < 4; ++slice) {
      const auto expected = expected_slice_words(weight_init.beat(addr), slice);
      const auto observed = observed_slice_words((*(memories[bank * 4 + slice]))[bank_addr]);
      if (observed != expected) {
        std::ostringstream oss;
        oss << "Top ffndown weight memory mismatch"
            << " addr=" << addr
            << " bank=" << bank
            << " bank_addr=" << bank_addr
            << " slice=" << slice
            << " observed=0x" << std::hex << observed[2] << "_" << observed[1] << "_" << observed[0]
            << " expected=0x" << expected[2] << "_" << expected[1] << "_" << expected[0];
        throw std::runtime_error(oss.str());
      }
    }
  }
}

const uint32_t* ffndown_bias_words(const VTop& dut, std::size_t idx) {
  switch (idx) {
    case 0: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_0;
    case 1: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_1;
    case 2: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_2;
    case 3: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_3;
    case 4: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_4;
    case 5: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_5;
    case 6: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_6;
    case 7: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_7;
    case 8: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_8;
    case 9: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_9;
    case 10: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_10;
    case 11: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_11;
    case 12: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_12;
    case 13: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_13;
    case 14: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_14;
    case 15: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_15;
    case 16: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_16;
    case 17: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_17;
    case 18: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_18;
    case 19: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_19;
    case 20: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_20;
    case 21: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_21;
    case 22: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_22;
    case 23: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_23;
    case 24: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_24;
    case 25: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_25;
    case 26: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_26;
    case 27: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_27;
    case 28: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_28;
    case 29: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_29;
    case 30: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_30;
    case 31: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_31;
    case 32: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_32;
    case 33: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_33;
    case 34: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_34;
    case 35: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_35;
    case 36: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_36;
    case 37: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_37;
    case 38: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_38;
    case 39: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_39;
    case 40: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_40;
    case 41: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_41;
    case 42: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_42;
    case 43: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_43;
    case 44: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_44;
    case 45: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_45;
    case 46: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_46;
    case 47: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_47;
    case 48: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_48;
    case 49: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_49;
    case 50: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_50;
    case 51: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_51;
    case 52: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_52;
    case 53: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_53;
    case 54: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_54;
    case 55: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_55;
    case 56: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_56;
    case 57: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_57;
    case 58: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_58;
    case 59: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_59;
    case 60: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_60;
    case 61: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_61;
    case 62: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_62;
    case 63: return dut.rootp->Top__DOT__ffndown__DOT__bias_mem_63;
    default: return nullptr;
  }
}

void probe_ffndown_bias_memory(const VTop& dut, const PackedWords& bias_init) {
  for (std::size_t beat = 0; beat < bias_init.beats(); ++beat) {
    const uint32_t* observed = ffndown_bias_words(dut, beat);
    require(observed != nullptr, "Top ffndown bias memory index out of range");
    for (std::size_t word = 0; word < 12; ++word) {
      if (observed[word] != bias_init.beat(beat)[word]) {
        std::ostringstream oss;
        oss << "Top ffndown bias memory mismatch"
            << " beat=" << beat
            << " word=" << word
            << " observed=0x" << std::hex << observed[word]
            << " expected=0x" << bias_init.beat(beat)[word];
        throw std::runtime_error(oss.str());
      }
    }
  }
}

}  // namespace

int main(int argc, char** argv) {
  try {
    Verilated::commandArgs(argc, argv);
    require(argc == 2, "usage: VTop <window_dir>");
    const std::filesystem::path window_dir = argv[1];
    const auto cfg = read_cfg(window_dir / "window.cfg");

    const auto data_in = read_words(window_dir / "artifacts" / "data_in.u32.bin", 12);
    const auto ln1_weights = read_words(window_dir / "artifacts" / "ln1_weights.u32.bin", 12);
    const auto ln2_weights = read_words(window_dir / "artifacts" / "ln2_weights.u32.bin", 12);
    const auto qkv_weight_init = read_words(window_dir / "artifacts" / "qkv_weight_init.u32.bin", 9);
    const auto qkv_bias_init = read_words(window_dir / "artifacts" / "qkv_bias_init.u32.bin", 3);
    const auto qkv_q_chunk0 = read_words(window_dir / "artifacts" / "qkv_q_chunk0.u32.bin", 3);
    const auto qkv_k_chunk0 = read_words(window_dir / "artifacts" / "qkv_k_chunk0.u32.bin", 3);
    const auto qkv_v_chunk0 = read_words(window_dir / "artifacts" / "qkv_v_chunk0.u32.bin", 3);
    const auto sm_masks = read_words(window_dir / "artifacts" / "sm_masks.u32.bin", 1);
    const auto qk_head0_golden = read_words(window_dir / "artifacts" / "qk_head0_golden.u32.bin", 26);
    const auto softmax_input_head0 = read_words(window_dir / "artifacts" / "softmax_input_head0.u32.bin", 26);
    const auto softmax_head0_golden = read_words(window_dir / "artifacts" / "softmax_head0_golden.u32.bin", 26);
    const auto out_weight_init = read_words(window_dir / "artifacts" / "out_weight_init.u32.bin", 9);
    const auto out_bias_init = read_words(window_dir / "artifacts" / "out_bias_init.u32.bin", 12);
    const auto out_proj_input = read_words(window_dir / "artifacts" / "out_proj_input.u32.bin", 16);
    const auto out_proj_golden = read_words(window_dir / "artifacts" / "out_proj_golden.u32.bin", 12);
    const auto pv_v_in = read_words(
        window_dir.parent_path().parent_path() / "pv" / "head_0_tok_0_1" / "artifacts" / "v_in.u32.bin",
        16);
    const auto ffnup_weight_init = read_words(window_dir / "artifacts" / "ffnup_weight_init.u32.bin", 9);
    const auto ffnup_bias_init = read_words(window_dir / "artifacts" / "ffnup_bias_init.u32.bin", 3);
    const auto ffnup_golden = read_words(window_dir / "artifacts" / "ffnup_golden.u32.bin", 3);
    const auto ffndown_weight_init = read_words(window_dir / "artifacts" / "ffndown_weight_init.u32.bin", 9);
    const auto ffndown_bias_init = read_words(window_dir / "artifacts" / "ffndown_bias_init.u32.bin", 12);
    const auto ffndown_golden = read_words(window_dir / "artifacts" / "ffndown_golden.u32.bin", 12);
    const auto resadd1_golden = read_words(window_dir / "artifacts" / "resadd1_golden.u32.bin", 12);
    const auto golden = read_words(window_dir / "artifacts" / "golden.u32.bin", 12);
    const int resadd2_probe_beat = std::getenv("TOP_RESADD2_PROBE_BEAT")
        ? std::atoi(std::getenv("TOP_RESADD2_PROBE_BEAT"))
        : -1;
    const bool debug = std::getenv("TOP_DEBUG") != nullptr;
    const bool allow_dm1_mismatch = std::getenv("TOP_ALLOW_DM1_MISMATCH") != nullptr;
    const bool allow_outlinear_raw_mismatch = std::getenv("TOP_ALLOW_OUTLINEAR_RAW_MISMATCH") != nullptr;
    const int ffndown_detail_sample =
        std::getenv("FFNDOWN_DETAIL_SAMPLE") ? std::atoi(std::getenv("FFNDOWN_DETAIL_SAMPLE")) : -1;
    int debug_left = 12;
    int outlinear_debug_left = 4;
    int orig_debug_left = 8;

    VTop dut;
    dut.io_layer_st = 0;
    dut.io_cfg_valid = 0;
    dut.io_cfg_prefill = 1;
    dut.io_cfg_seqlen = cfg_int(cfg, "cfg_seqlen");
    dut.io_attn_cfg_valid = 0;
    dut.io_attn_cfg_prefill = 1;
    dut.io_attn_cfg_seqlen = cfg_int(cfg, "cfg_seqlen");
    dut.io_attn_cfg_single_query = 0;
    dut.io_weight_init_mode = 0;
    dut.io_data_in_ready = 0;
    dut.io_ln_w_valid = 0;
    dut.io_qkv_b_valid = 0;
    dut.io_out_b_valid = 0;
    dut.io_ln2_w_valid = 0;
    dut.io_ffnup_b_valid = 0;
    dut.io_ffndown_b_valid = 0;
    dut.io_res_ready = 1;
    dut.io_ln1_out_inv_scale = cfg_u32(cfg, "ln1_out_inv_scale_u32");
    dut.io_ln1_out_zero_point = static_cast<int8_t>(cfg_int(cfg, "ln1_out_zero_point_s8"));
    dut.io_q_out_inv_scale = cfg_u32(cfg, "q_out_inv_scale_u32");
    dut.io_k_out_inv_scale = cfg_u32(cfg, "k_out_inv_scale_u32");
    dut.io_v_out_inv_scale = cfg_u32(cfg, "v_out_inv_scale_u32");
    dut.io_q_bias_scale = cfg_u32(cfg, "q_bias_scale_u32");
    dut.io_k_bias_scale = cfg_u32(cfg, "k_bias_scale_u32");
    dut.io_v_bias_scale = cfg_u32(cfg, "v_bias_scale_u32");
    dut.io_dm1_out_scale = cfg_u32(cfg, "dm1_out_scale_u32");
    dut.io_dm2_ctx_inv_scale = cfg_u32(cfg, "dm2_ctx_inv_scale_u32");
    dut.io_dm2_ctx_zero_point = static_cast<uint8_t>(cfg_int(cfg, "dm2_ctx_zero_point_u8"));
    dut.io_dm2_out_inv_scale = cfg_u32(cfg, "dm2_out_inv_scale_u32");
    dut.io_out_out_scale = cfg_u32(cfg, "out_out_scale_u32");
    dut.io_ln2_out_inv_scale = cfg_u32(cfg, "ln2_out_inv_scale_u32");
    dut.io_ln2_out_zero_point = static_cast<int8_t>(cfg_int(cfg, "ln2_out_zero_point_s8"));
    dut.io_ffnup_out_inv_scale = cfg_u32(cfg, "ffnup_out_inv_scale_u32");
    dut.io_ffnup_bias_scale = cfg_u32(cfg, "ffnup_bias_scale_u32");
    dut.io_ffndown_out_scale = cfg_u32(cfg, "ffndown_out_scale_u32");
    zero_words(dut.io_data_in, 12);
    zero_words(dut.io_ln_w_in, 12);
    zero_words(dut.io_qkv_w_in, 9);
    zero_words(dut.io_qkv_b_in, 3);
    dut.io_sm_w_in = 0;
    zero_words(dut.io_out_w_in, 9);
    zero_words(dut.io_out_b_in, 12);
    zero_words(dut.io_ln2_w_in, 12);
    zero_words(dut.io_ffnup_w_in, 9);
    zero_words(dut.io_ffnup_b_in, 3);
    zero_words(dut.io_ffndown_w_in, 9);
    zero_words(dut.io_ffndown_b_in, 12);

    reset_with_ddr(dut, data_in, qkv_weight_init, sm_masks, out_weight_init, ffnup_weight_init, ffndown_weight_init);

    std::vector<bool> seen_qkv(qkv_weight_init.beats(), false);
    std::vector<bool> seen_out(out_weight_init.beats(), false);
    std::vector<bool> seen_ffnup(ffnup_weight_init.beats(), false);
    std::vector<bool> seen_ffndown(ffndown_weight_init.beats(), false);
    dut.io_weight_init_mode = 1;
    dut.io_layer_st = 1;
    const std::size_t max_weight_cycles =
        std::max({qkv_weight_init.beats(), out_weight_init.beats(), ffnup_weight_init.beats(), ffndown_weight_init.beats()}) * 6;
    for (std::size_t cycle = 0; cycle < max_weight_cycles; ++cycle) {
      if (dut.io_qkv_w_addr < qkv_weight_init.beats()) {
        seen_qkv[dut.io_qkv_w_addr] = true;
      }
      if (dut.io_out_w_addr < out_weight_init.beats()) {
        seen_out[dut.io_out_w_addr] = true;
      }
      if (dut.io_ffnup_w_addr < ffnup_weight_init.beats()) {
        seen_ffnup[dut.io_ffnup_w_addr] = true;
      }
      if (dut.io_ffndown_w_addr < ffndown_weight_init.beats()) {
        seen_ffndown[dut.io_ffndown_w_addr] = true;
      }
      if (debug && cycle > 0 && (cycle % 1000) == 0) {
        const auto count_seen = [](const std::vector<bool>& seen) -> std::size_t {
          return static_cast<std::size_t>(std::count(seen.begin(), seen.end(), true));
        };
        std::cerr << "Top weight init progress"
                  << " cycle=" << cycle
                  << " qkv_addr=" << dut.io_qkv_w_addr << "/" << qkv_weight_init.beats()
                  << " out_addr=" << dut.io_out_w_addr << "/" << out_weight_init.beats()
                  << " ffnup_addr=" << dut.io_ffnup_w_addr << "/" << ffnup_weight_init.beats()
                  << " ffndown_addr=" << dut.io_ffndown_w_addr << "/" << ffndown_weight_init.beats()
                  << " seen_qkv=" << count_seen(seen_qkv)
                  << " seen_out=" << count_seen(seen_out)
                  << " seen_ffnup=" << count_seen(seen_ffnup)
                  << " seen_ffndown=" << count_seen(seen_ffndown)
                  << std::endl;
      }
      tick_with_ddr(dut, data_in, qkv_weight_init, sm_masks, out_weight_init, ffnup_weight_init, ffndown_weight_init);
      if (all_seen(seen_qkv) && all_seen(seen_out) && all_seen(seen_ffnup) && all_seen(seen_ffndown)) {
        break;
      }
    }
    require(all_seen(seen_qkv), "Top qkv weight init did not cover all addresses");
    require(all_seen(seen_out), "Top out_proj weight init did not cover all addresses");
    require(all_seen(seen_ffnup), "Top ffnup weight init did not cover all addresses");
    require(all_seen(seen_ffndown), "Top ffndown weight init did not cover all addresses");
    if (debug) {
      std::cerr << "Top weight init done"
                << " qkv=" << qkv_weight_init.beats()
                << " out=" << out_weight_init.beats()
                << " ffnup=" << ffnup_weight_init.beats()
                << " ffndown=" << ffndown_weight_init.beats()
                << std::endl;
    }
    dut.io_weight_init_mode = 0;
    dut.io_layer_st = 0;
    tick_with_ddr(dut, data_in, qkv_weight_init, sm_masks, out_weight_init, ffnup_weight_init, ffndown_weight_init);

    reset_with_ddr(dut, data_in, qkv_weight_init, sm_masks, out_weight_init, ffnup_weight_init, ffndown_weight_init);
    probe_ffndown_weight_memory(dut, ffndown_weight_init);
    std::cerr << "Top ffndown weight memory probe PASS addresses=" << ffndown_weight_init.beats() << std::endl;
    dut.io_layer_st = 0;
    dut.io_cfg_valid = 0;
    dut.io_cfg_prefill = 1;
    dut.io_cfg_seqlen = cfg_int(cfg, "cfg_seqlen");
    dut.io_attn_cfg_valid = 0;
    dut.io_attn_cfg_prefill = 1;
    dut.io_attn_cfg_seqlen = cfg_int(cfg, "cfg_seqlen");
    dut.io_attn_cfg_single_query = 0;
    dut.io_weight_init_mode = 0;
    dut.io_data_in_ready = 0;
    dut.io_res_ready = 1;
    dut.io_ln1_out_inv_scale = cfg_u32(cfg, "ln1_out_inv_scale_u32");
    dut.io_ln1_out_zero_point = static_cast<int8_t>(cfg_int(cfg, "ln1_out_zero_point_s8"));
    dut.io_q_out_inv_scale = cfg_u32(cfg, "q_out_inv_scale_u32");
    dut.io_k_out_inv_scale = cfg_u32(cfg, "k_out_inv_scale_u32");
    dut.io_v_out_inv_scale = cfg_u32(cfg, "v_out_inv_scale_u32");
    dut.io_q_bias_scale = cfg_u32(cfg, "q_bias_scale_u32");
    dut.io_k_bias_scale = cfg_u32(cfg, "k_bias_scale_u32");
    dut.io_v_bias_scale = cfg_u32(cfg, "v_bias_scale_u32");
    dut.io_dm1_out_scale = cfg_u32(cfg, "dm1_out_scale_u32");
    dut.io_dm2_ctx_inv_scale = cfg_u32(cfg, "dm2_ctx_inv_scale_u32");
    dut.io_dm2_ctx_zero_point = static_cast<uint8_t>(cfg_int(cfg, "dm2_ctx_zero_point_u8"));
    dut.io_dm2_out_inv_scale = cfg_u32(cfg, "dm2_out_inv_scale_u32");
    dut.io_out_out_scale = cfg_u32(cfg, "out_out_scale_u32");
    dut.io_ln2_out_inv_scale = cfg_u32(cfg, "ln2_out_inv_scale_u32");
    dut.io_ln2_out_zero_point = static_cast<int8_t>(cfg_int(cfg, "ln2_out_zero_point_s8"));
    dut.io_ffnup_out_inv_scale = cfg_u32(cfg, "ffnup_out_inv_scale_u32");
    dut.io_ffnup_bias_scale = cfg_u32(cfg, "ffnup_bias_scale_u32");
    dut.io_ffndown_out_scale = cfg_u32(cfg, "ffndown_out_scale_u32");

    stream_words(
        dut,
        dut.io_ln_w_in,
        dut.io_ln_w_valid,
        ln1_weights,
        12,
        data_in,
        qkv_weight_init,
        sm_masks,
        out_weight_init,
        ffnup_weight_init,
        ffndown_weight_init);
    stream_words(
        dut,
        dut.io_ln2_w_in,
        dut.io_ln2_w_valid,
        ln2_weights,
        12,
        data_in,
        qkv_weight_init,
        sm_masks,
        out_weight_init,
        ffnup_weight_init,
        ffndown_weight_init);
    stream_words(
        dut,
        dut.io_qkv_b_in,
        dut.io_qkv_b_valid,
        qkv_bias_init,
        3,
        data_in,
        qkv_weight_init,
        sm_masks,
        out_weight_init,
        ffnup_weight_init,
        ffndown_weight_init);
    stream_words(
        dut,
        dut.io_out_b_in,
        dut.io_out_b_valid,
        out_bias_init,
        12,
        data_in,
        qkv_weight_init,
        sm_masks,
        out_weight_init,
        ffnup_weight_init,
        ffndown_weight_init);
    stream_words(
        dut,
        dut.io_ffnup_b_in,
        dut.io_ffnup_b_valid,
        ffnup_bias_init,
        3,
        data_in,
        qkv_weight_init,
        sm_masks,
        out_weight_init,
        ffnup_weight_init,
        ffndown_weight_init);
    stream_words(
        dut,
        dut.io_ffndown_b_in,
        dut.io_ffndown_b_valid,
        ffndown_bias_init,
        12,
        data_in,
        qkv_weight_init,
        sm_masks,
        out_weight_init,
        ffnup_weight_init,
        ffndown_weight_init);
    if (debug) {
      std::cerr << "Top streamed init done"
                << " ln1=" << ln1_weights.beats()
                << " ln2=" << ln2_weights.beats()
                << " qkv_bias=" << qkv_bias_init.beats()
                << " out_bias=" << out_bias_init.beats()
                << " ffnup_bias=" << ffnup_bias_init.beats()
                << " ffndown_bias=" << ffndown_bias_init.beats()
                << std::endl;
    }
    probe_ffndown_bias_memory(dut, ffndown_bias_init);
    if (debug) {
      std::cerr << "Top ffndown bias memory probe PASS addresses=" << ffndown_bias_init.beats() << std::endl;
    }

    dut.io_cfg_valid = 1;
    dut.io_attn_cfg_valid = 1;
    tick_with_ddr(dut, data_in, qkv_weight_init, sm_masks, out_weight_init, ffnup_weight_init, ffndown_weight_init);
    dut.io_cfg_valid = 0;
    dut.io_attn_cfg_valid = 0;
    dut.io_layer_st = 1;
    tick_with_ddr(dut, data_in, qkv_weight_init, sm_masks, out_weight_init, ffnup_weight_init, ffndown_weight_init);
    dut.io_layer_st = 0;
    std::size_t warmup_ffndown_lw_beats = 0;
    for (int cycle = 0; cycle < 64; ++cycle) {
      tick_with_ddr(dut, data_in, qkv_weight_init, sm_masks, out_weight_init, ffnup_weight_init, ffndown_weight_init);
      if (debug && warmup_ffndown_lw_beats < 8 &&
          dut.rootp->Top__DOT__ffndown__DOT__lw_inst__DOT__io_data_out_valid_REG) {
        std::cerr << "Top ffndown warmup-lw trace"
                  << " sample=" << warmup_ffndown_lw_beats
                  << " addr_reg=" << dut.rootp->Top__DOT__ffndown__DOT__lw_inst__DOT__addr_reg
                  << " state=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__lw_inst__DOT__state)
                  << " row_cnt=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__lw_inst__DOT__row_cnt_r)
                  << " rowblock_cnt=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__lw_inst__DOT__rowblock_cnt_r)
                  << " colblock_cnt=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__lw_inst__DOT__colblock_cnt_r)
                  << std::endl;
        ++warmup_ffndown_lw_beats;
      }
    }
    dut.io_data_in_ready = 1;
    if (debug) {
      std::cerr << "Top run armed"
                  << " cfg_seqlen=" << cfg_int(cfg, "cfg_seqlen")
                << " input_beats=" << data_in.beats()
                << " output_beats=" << golden.beats()
                << std::endl;
    }

    std::vector<uint32_t> observed(golden.words.size(), 0);
    std::vector<bool> seen(golden.beats(), false);
    bool saw_st = false;
    bool saw_last = false;
    std::size_t seen_resadd1_beats = 0;
    std::size_t seen_dm1_beats = 0;
    std::size_t seen_dm2_beats = 0;
    std::size_t seen_softmax_beats = 0;
    std::size_t seen_outlinear_beats = 0;
    std::size_t seen_ffnup_beats = 0;
    std::size_t seen_ffndown_beats = 0;
    std::size_t seen_ffndown_input_beats = 0;
    std::size_t seen_ffndown_lw_beats = 0;
    bool checked_dm1 = false;
    bool checked_softmax = false;
    bool checked_dm2 = false;
    bool checked_qkv_buf0 = false;
    bool checked_headbuf0 = false;
    bool checked_resadd1 = false;
    bool checked_outlinear = false;
    bool checked_ffnup = false;
    bool checked_ffndown = false;
    bool checked_ffndown_input = false;
    bool probed_ffnup_tolerated_diff = false;
    bool probed_ffndown_cu_start = false;
    bool probed_ffndown_su_first = false;
    bool probed_ffndown_write_last = false;
    std::size_t seen_ffndown_runtime_weight_writes = 0;
    std::size_t seen_ffndown_mul_sources = 0;
    std::size_t seen_ffndown_psum_updates = 0;
    std::size_t seen_ffndown_writes = 0;
    int prev_ffndown_cu_state = 0;
    int prev_ffndown_mul_in_match = -2;
    uint32_t prev_ffndown_psum0 = dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__psums0_0_0;
    uint32_t prev_ffndown_psum1 = dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__psums0_0_1;
    uint32_t prev_ffndown_psum2 = dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__psums0_0_2;
    uint32_t prev_ffndown_psum3 = dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__psums0_0_3;
    struct FFNDownSourceEvent {
      std::size_t in_match;
      bool w_r_sel;
      std::size_t row0_beat;
      std::size_t row11_beat;
      std::array<int, 12> in_lanes;
      std::array<std::array<int, 12>, 4> w_cols;
      std::array<std::array<int, 12>, 4> prod_cols;
      std::array<int, 4> sum_cols;
    };
    std::deque<FFNDownSourceEvent> ffndown_src_hist;
    std::vector<float> ffndown_su_first_raw;
    std::vector<float> ffndown_su_first_bias;
    int ffndown_su_first_addr = -1;
    bool ffndown_su_first_st = false;

    for (int cycle = 0; cycle < 2000000 && !all_seen(seen); ++cycle) {
      tick_with_ddr(dut, data_in, qkv_weight_init, sm_masks, out_weight_init, ffnup_weight_init, ffndown_weight_init);
      if (!checked_qkv_buf0 && dut.rootp->Top__DOT__qkvlinear__DOT__state == 2) {
        report_int8_mismatch(
            "Top.qkv_buf_q0",
            0,
            dut.rootp->Top__DOT__qkvlinear__DOT__vec_buffer_0_0,
            qkv_q_chunk0.beat(0),
            3,
            12);
        report_int8_mismatch(
            "Top.qkv_buf_k0",
            0,
            dut.rootp->Top__DOT__qkvlinear__DOT__vec_buffer_0_64,
            qkv_k_chunk0.beat(0),
            3,
            12);
        report_int8_mismatch(
            "Top.qkv_buf_v0",
            0,
            dut.rootp->Top__DOT__qkvlinear__DOT__vec_buffer_0_128,
            qkv_v_chunk0.beat(0),
            3,
            12);
        if (debug) {
          const auto obs_q = unpack_int8_lanes(dut.rootp->Top__DOT__qkvlinear__DOT__vec_buffer_0_0, 12);
          const auto exp_q = unpack_int8_lanes(qkv_q_chunk0.beat(0), 12);
          bool exact_q = true;
          std::size_t diff_q_lane = 0;
          int diff_q_obs = 0;
          int diff_q_exp = 0;
          for (std::size_t lane = 0; lane < 12; ++lane) {
            if (obs_q[lane] != exp_q[lane]) {
              exact_q = false;
              diff_q_lane = lane;
              diff_q_obs = obs_q[lane];
              diff_q_exp = exp_q[lane];
              break;
            }
          }
          if (exact_q) {
            std::cerr << "Top qkv vec_buffer q0 exact match" << std::endl;
          } else {
            std::cerr << "Top qkv vec_buffer q0 tolerated diff"
                      << " lane=" << diff_q_lane
                      << " observed=" << diff_q_obs
                      << " expected=" << diff_q_exp
                      << std::endl;
          }

          const auto obs_k = unpack_int8_lanes(dut.rootp->Top__DOT__qkvlinear__DOT__vec_buffer_0_64, 12);
          const auto exp_k = unpack_int8_lanes(qkv_k_chunk0.beat(0), 12);
          bool exact_k = true;
          std::size_t diff_k_lane = 0;
          int diff_k_obs = 0;
          int diff_k_exp = 0;
          for (std::size_t lane = 0; lane < 12; ++lane) {
            if (obs_k[lane] != exp_k[lane]) {
              exact_k = false;
              diff_k_lane = lane;
              diff_k_obs = obs_k[lane];
              diff_k_exp = exp_k[lane];
              break;
            }
          }
          if (exact_k) {
            std::cerr << "Top qkv vec_buffer k0 exact match" << std::endl;
          } else {
            std::cerr << "Top qkv vec_buffer k0 tolerated diff"
                      << " lane=" << diff_k_lane
                      << " observed=" << diff_k_obs
                      << " expected=" << diff_k_exp
                      << std::endl;
          }

          const auto obs = unpack_int8_lanes(dut.rootp->Top__DOT__qkvlinear__DOT__vec_buffer_0_128, 12);
          const auto exp = unpack_int8_lanes(qkv_v_chunk0.beat(0), 12);
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
            std::cerr << "Top qkv vec_buffer v0 exact match" << std::endl;
          } else {
            std::cerr << "Top qkv vec_buffer v0 tolerated diff"
                      << " lane=" << diff_lane
                      << " observed=" << diff_obs
                      << " expected=" << diff_exp
                      << std::endl;
          }
        }
        checked_qkv_buf0 = true;
        if (debug) {
          std::cerr << "Top qkv vec_buffer chunk0 matches staged q/k/v" << std::endl;
        }
      }
      if (!checked_dm1 &&
          dut.rootp->__Vdlyvset__Top__DOT__atten__DOT__softmax__DOT__memInst__DOT__mem__DOT__mem_ext__DOT__Memory__v0 &&
          dut.rootp->__Vdlyvdim0__Top__DOT__atten__DOT__softmax__DOT__memInst__DOT__mem__DOT__mem_ext__DOT__Memory__v0 == 0) {
        require(seen_dm1_beats < qk_head0_golden.beats(), "Top dm1 produced too many beats");
        try {
          report_fp32_mismatch(
              "Top.dm1",
              seen_dm1_beats,
              dut.rootp->Top__DOT__atten__DOT__softmax__DOT__memInst__DOT__mem__DOT__mem_ext__DOT__Memory[0],
              qk_head0_golden.beat(seen_dm1_beats),
              26);
        } catch (const std::exception& ex) {
          if (!allow_dm1_mismatch) {
            throw;
          }
          if (debug) {
            std::cerr << ex.what() << " (ignored by TOP_ALLOW_DM1_MISMATCH)" << std::endl;
            std::cerr << "Top dm1 row0 prefix observed="
                      << fp32_prefix(
                             dut.rootp->Top__DOT__atten__DOT__softmax__DOT__memInst__DOT__mem__DOT__mem_ext__DOT__Memory[0],
                             26,
                             4)
                      << " expected_qk="
                      << fp32_prefix(qk_head0_golden.beat(seen_dm1_beats), 26, 4)
                      << " expected_softmax_in="
                      << fp32_prefix(softmax_input_head0.beat(seen_dm1_beats), 26, 4)
                      << std::endl;
            std::cerr << "Top dm1 row1 prefix observed="
                      << fp32_prefix(
                             dut.rootp->Top__DOT__atten__DOT__softmax__DOT__memInst__DOT__mem__DOT__mem_ext__DOT__Memory[1],
                             26,
                             4)
                      << std::endl;
          }
        }
        ++seen_dm1_beats;
        if (seen_dm1_beats == qk_head0_golden.beats()) {
          checked_dm1 = true;
          if (debug) {
            std::cerr << "Top dm1 matches staged golden" << std::endl;
          }
        }
      }
      if (!checked_softmax && dut.rootp->Top__DOT__atten__DOT__softmax__DOT__suInst__DOT__io_data_out_valid_REG) {
        require(seen_softmax_beats < softmax_head0_golden.beats(), "Top softmax produced too many beats");
        if (debug) {
          std::string mask_bits;
          mask_bits.reserve(26);
          const auto push_mask = [&mask_bits](CData bit) {
            mask_bits.push_back(bit ? '1' : '0');
          };
          push_mask(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_25);
          push_mask(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_24);
          push_mask(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_23);
          push_mask(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_22);
          push_mask(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_21);
          push_mask(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_20);
          push_mask(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_19);
          push_mask(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_18);
          push_mask(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_17);
          push_mask(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_16);
          push_mask(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_15);
          push_mask(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_14);
          push_mask(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_13);
          push_mask(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_12);
          push_mask(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_11);
          push_mask(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_10);
          push_mask(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_9);
          push_mask(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_8);
          push_mask(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_7);
          push_mask(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_6);
          push_mask(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_5);
          push_mask(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_4);
          push_mask(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_3);
          push_mask(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_2);
          push_mask(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_1);
          push_mask(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_0);
          std::cerr << "Top softmax probe"
                    << " beat=" << seen_softmax_beats
                    << " sm_w_addr=" << dut.io_sm_w_addr
                    << " sm_lw_state=" << static_cast<int>(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__lwInst__DOT__state)
                    << " sm_lw_valid=" << static_cast<int>(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__lwInst__DOT__io_data_out_valid_REG)
                    << " sm_lw_prefill_cnt=" << static_cast<int>(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__lwInst__DOT__prefill_batchsize_cnt_r)
                    << " sm_lu_valid=" << static_cast<int>(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__luInst__DOT__io_data_out_valid_REG)
                    << " sm_any=" << static_cast<int>(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__cuInst__DOT__softmax__DOT__anyValid)
                    << " sm_mask_bits=" << mask_bits
                    << std::endl;
        }
        report_fp32_mismatch(
            "Top.softmax",
            seen_softmax_beats,
            dut.rootp->Top__DOT__atten__DOT__softmax__DOT__suInst__DOT__io_data_out_REG,
            softmax_head0_golden.beat(seen_softmax_beats),
            26);
        if (debug && seen_softmax_beats == 0) {
          const uint32_t* observed =
              &dut.rootp->Top__DOT__atten__DOT__softmax__DOT__suInst__DOT__io_data_out_REG[0];
          const auto* golden_words = softmax_head0_golden.beat(seen_softmax_beats);
          const auto obs = unpack_fp32_lanes(observed, 26);
          const auto exp = unpack_fp32_lanes(golden_words, 26);
          bool exact_same = true;
          std::size_t diff_lane = 0;
          float diff_obs = 0.0f;
          float diff_exp = 0.0f;
          float diff_abs = 0.0f;
          for (std::size_t lane = 0; lane < 26; ++lane) {
            if (observed[lane] != golden_words[lane]) {
              const float abs_err = std::abs(obs[lane] - exp[lane]);
              if (abs_err <= 5.0e-4f) {
                exact_same = false;
                diff_lane = lane;
                diff_obs = obs[lane];
                diff_exp = exp[lane];
                diff_abs = abs_err;
                break;
              }
            }
          }
          if (exact_same) {
            std::cerr << "Top softmax beat0 exact-or-hard match" << std::endl;
          } else {
            std::cerr << "Top softmax beat0 tolerated diff"
                      << " lane=" << diff_lane
                      << " observed=" << diff_obs
                      << " expected=" << diff_exp
                      << " abs_err=" << diff_abs
                      << std::endl;
          }
        }
        ++seen_softmax_beats;
        if (seen_softmax_beats == softmax_head0_golden.beats()) {
          checked_softmax = true;
          if (debug) {
            std::cerr << "Top softmax matches staged golden" << std::endl;
          }
        }
      }
      if (!checked_dm2 && dut.rootp->Top__DOT__atten__DOT__dm2__DOT__dmInst__DOT__io_data_out_valid_REG) {
        require(seen_dm2_beats < out_proj_input.beats(), "Top dm2 produced too many beats");
        if (debug && debug_left > 0) {
          std::cerr << "Top dm2 probe"
                    << " beat=" << seen_dm2_beats
                    << " sm0=" << u32_to_float(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__suInst__DOT__io_data_out_REG[0])
                    << " sm1=" << u32_to_float(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__suInst__DOT__io_data_out_REG[1])
                    << " loadu_v=" << static_cast<int>(dut.rootp->Top__DOT__atten__DOT__dm2__DOT__loaduInst__DOT__io_data_out_v_valid_REG)
                    << " loadu_ctx=" << static_cast<int>(dut.rootp->Top__DOT__atten__DOT__dm2__DOT__loaduInst__DOT__io_data_out_ctx_valid_REG)
                    << " loadu_state=" << static_cast<int>(dut.rootp->Top__DOT__atten__DOT__dm2__DOT__loaduInst__DOT__st_state)
                    << " dm_state=" << static_cast<int>(dut.rootp->Top__DOT__atten__DOT__dm2__DOT__dmInst__DOT__state)
                    << " ctx0=" << decode_u8_lane(dut.rootp->Top__DOT__atten__DOT__dm2__DOT__dmInst__DOT__ctx, 0)
                    << " ctx1=" << decode_u8_lane(dut.rootp->Top__DOT__atten__DOT__dm2__DOT__dmInst__DOT__ctx, 1)
                    << " v00=" << decode_i8_lane(dut.rootp->Top__DOT__atten__DOT__dm2__DOT__dmInst__DOT__vBuf_0_r, 0)
                    << " v10=" << decode_i8_lane(dut.rootp->Top__DOT__atten__DOT__dm2__DOT__dmInst__DOT__vBuf_1_r, 0)
                    << " out0=" << decode_i8_lane(dut.rootp->Top__DOT__atten__DOT__dm2__DOT___dmInst_io_data_out, 0)
                    << " gold0=" << decode_i8_lane(out_proj_input.beat(seen_dm2_beats), 0)
                    << std::endl;
          --debug_left;
        }
        report_int8_mismatch(
            "Top.dm2",
            seen_dm2_beats,
            dut.rootp->Top__DOT__atten__DOT__dm2__DOT___dmInst_io_data_out,
            out_proj_input.beat(seen_dm2_beats),
            16,
            64);
        if (debug && seen_dm2_beats == 0) {
          const auto obs = unpack_int8_lanes(dut.rootp->Top__DOT__atten__DOT__dm2__DOT___dmInst_io_data_out, 64);
          const auto exp = unpack_int8_lanes(out_proj_input.beat(seen_dm2_beats), 64);
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
            std::cerr << "Top dm2 beat0 exact match" << std::endl;
          } else {
            std::cerr << "Top dm2 beat0 tolerated diff"
                      << " lane=" << diff_lane
                      << " observed=" << diff_obs
                      << " expected=" << diff_exp
                      << std::endl;
          }
          const auto v_obs = unpack_int8_lanes(dut.rootp->Top__DOT__atten__DOT__dm2__DOT__dmInst__DOT__vBuf_0_r, 64);
          const auto v_exp = unpack_int8_lanes(pv_v_in.beat(0), 64);
          bool v_exact_same = true;
          std::size_t v_diff_lane = 0;
          int v_diff_obs = 0;
          int v_diff_exp = 0;
          for (std::size_t lane = 0; lane < 64; ++lane) {
            if (v_obs[lane] != v_exp[lane]) {
              v_exact_same = false;
              v_diff_lane = lane;
              v_diff_obs = v_obs[lane];
              v_diff_exp = v_exp[lane];
              break;
            }
          }
          if (v_exact_same) {
            std::cerr << "Top dm2 vbuf0 beat0 exact match" << std::endl;
          } else {
            std::cerr << "Top dm2 vbuf0 beat0 tolerated diff"
                      << " lane=" << v_diff_lane
                      << " observed=" << v_diff_obs
                      << " expected=" << v_diff_exp
                      << std::endl;
          }
        }
        ++seen_dm2_beats;
        if (seen_dm2_beats == out_proj_input.beats()) {
          checked_dm2 = true;
          if (debug) {
            std::cerr << "Top dm2 matches staged attention output" << std::endl;
          }
        }
      }
      if (!checked_headbuf0 && dut.rootp->Top__DOT__outlinear__DOT__state == 2) {
        const uint32_t* headbufs[] = {
            dut.rootp->Top__DOT__outlinear__DOT__head_buffer_0_0,
            dut.rootp->Top__DOT__outlinear__DOT__head_buffer_0_1,
            dut.rootp->Top__DOT__outlinear__DOT__head_buffer_0_2,
            dut.rootp->Top__DOT__outlinear__DOT__head_buffer_0_3,
            dut.rootp->Top__DOT__outlinear__DOT__head_buffer_0_4,
            dut.rootp->Top__DOT__outlinear__DOT__head_buffer_0_5,
            dut.rootp->Top__DOT__outlinear__DOT__head_buffer_0_6,
            dut.rootp->Top__DOT__outlinear__DOT__head_buffer_0_7,
            dut.rootp->Top__DOT__outlinear__DOT__head_buffer_0_8,
            dut.rootp->Top__DOT__outlinear__DOT__head_buffer_0_9,
            dut.rootp->Top__DOT__outlinear__DOT__head_buffer_0_10,
            dut.rootp->Top__DOT__outlinear__DOT__head_buffer_0_11,
        };
        for (std::size_t h = 0; h < 12; ++h) {
          report_int8_mismatch(
              "Top.outlinear_headbuf0",
              h,
              headbufs[h],
              out_proj_input.beat(h),
              16,
              64);
        }
        if (debug) {
          bool exact_same = true;
          std::size_t diff_head = 0;
          std::size_t diff_lane = 0;
          int diff_obs = 0;
          int diff_exp = 0;
          for (std::size_t h = 0; h < 12 && exact_same; ++h) {
            const auto obs = unpack_int8_lanes(headbufs[h], 64);
            const auto exp = unpack_int8_lanes(out_proj_input.beat(h), 64);
            for (std::size_t lane = 0; lane < 64; ++lane) {
              if (obs[lane] != exp[lane]) {
                exact_same = false;
                diff_head = h;
                diff_lane = lane;
                diff_obs = obs[lane];
                diff_exp = exp[lane];
                break;
              }
            }
          }
          if (exact_same) {
            std::cerr << "Top outlinear head_buffer token0 exact match" << std::endl;
          } else {
            std::cerr << "Top outlinear head_buffer token0 tolerated diff"
                      << " head=" << diff_head
                      << " lane=" << diff_lane
                      << " observed=" << diff_obs
                      << " expected=" << diff_exp
                      << std::endl;
          }
        }
        checked_headbuf0 = true;
        if (debug) {
          std::cerr << "Top outlinear head_buffer token0[0..11] matches staged attention output" << std::endl;
        }
      }
      if (debug && orig_debug_left > 0 && dut.rootp->Top__DOT__ln_addr_gen__DOT__io_data_valid_REG) {
        const auto data = unpack_fp32_lanes(dut.io_data_in, 12);
        std::cerr << "Top orig write probe"
                  << " mem_addr=" << static_cast<int>(dut.io_data_in_addr)
                  << " orig_addr=" << static_cast<int>(dut.rootp->Top__DOT__resadd_io_orig_in_addr_REG)
                  << " last=" << static_cast<int>(dut.rootp->Top__DOT__ln_addr_gen__DOT__io_data_last_REG)
                  << " data0=" << data[0]
                  << " data1=" << data[1]
                  << std::endl;
        --orig_debug_left;
      }
      if (!checked_outlinear && dut.rootp->Top__DOT__outlinear__DOT__su_inst__DOT__io_data_out_valid_REG) {
        require(seen_outlinear_beats < out_proj_golden.beats(), "Top outlinear produced too many beats");
        const auto obs = unpack_fp32_lanes(
            dut.rootp->Top__DOT__outlinear__DOT__su_inst__DOT__io_data_out_REG,
            12);
        const auto gold = unpack_fp32_lanes(out_proj_golden.beat(seen_outlinear_beats), 12);
        const auto bias = unpack_fp32_lanes(out_bias_init.beat(seen_outlinear_beats), 12);
        const auto* pipe_words =
            &dut.rootp->Top__DOT__outlinear__DOT__cu_inst__DOT__indata_pipreg__DOT__pipData_2_REG[0];
        const auto* su_words = &dut.rootp->Top__DOT__outlinear__DOT__su_inst__DOT__io_data_out_REG[0];
        const auto* bias_words = &dut.rootp->Top__DOT__outlinear__DOT____Vcellinp__bias_add__io_b[0];
        std::array<float, 12> final{};
        final[0] = u32_to_float(dut.rootp->__PVT__Top__DOT__outlinear__DOT__bias_add__DOT__add->io_out);
        final[1] = u32_to_float(dut.rootp->__PVT__Top__DOT__outlinear__DOT__bias_add__DOT__add_1->io_out);
        final[2] = u32_to_float(dut.rootp->__PVT__Top__DOT__outlinear__DOT__bias_add__DOT__add_2->io_out);
        final[3] = u32_to_float(dut.rootp->__PVT__Top__DOT__outlinear__DOT__bias_add__DOT__add_3->io_out);
        final[4] = u32_to_float(dut.rootp->__PVT__Top__DOT__outlinear__DOT__bias_add__DOT__add_4->io_out);
        final[5] = u32_to_float(dut.rootp->__PVT__Top__DOT__outlinear__DOT__bias_add__DOT__add_5->io_out);
        final[6] = u32_to_float(dut.rootp->__PVT__Top__DOT__outlinear__DOT__bias_add__DOT__add_6->io_out);
        final[7] = u32_to_float(dut.rootp->__PVT__Top__DOT__outlinear__DOT__bias_add__DOT__add_7->io_out);
        final[8] = u32_to_float(dut.rootp->__PVT__Top__DOT__outlinear__DOT__bias_add__DOT__add_8->io_out);
        final[9] = u32_to_float(dut.rootp->__PVT__Top__DOT__outlinear__DOT__bias_add__DOT__add_9->io_out);
        final[10] = u32_to_float(dut.rootp->__PVT__Top__DOT__outlinear__DOT__bias_add__DOT__add_10->io_out);
        final[11] = u32_to_float(dut.rootp->__PVT__Top__DOT__outlinear__DOT__bias_add__DOT__add_11->io_out);
        if (debug && outlinear_debug_left > 0) {
          std::cerr << "Top outlinear probe"
                    << " beat=" << seen_outlinear_beats
                    << " out_addr=" << static_cast<int>(dut.rootp->Top__DOT__outlinear__DOT__su_inst__DOT__io_data_out_addr_REG)
                    << " out_st=" << static_cast<int>(dut.rootp->Top__DOT__outlinear__DOT__su_inst__DOT__io_data_out_st_REG)
                    << " out_last=" << static_cast<int>(dut.rootp->Top__DOT__outlinear__DOT__su_inst__DOT__io_data_out_last_REG)
                    << " lw_state=" << static_cast<int>(dut.rootp->Top__DOT__outlinear__DOT__lw_inst__DOT__state)
                    << " lw_valid=" << static_cast<int>(dut.rootp->Top__DOT__outlinear__DOT__lw_inst__DOT__io_data_out_valid_REG)
                    << " lu_state=" << static_cast<int>(dut.rootp->Top__DOT__outlinear__DOT__lu_inst__DOT__state)
                    << " lu_valid=" << static_cast<int>(dut.rootp->Top__DOT__outlinear__DOT__lu_inst__DOT__io_data_out_valid_REG)
                    << " cu_state=" << static_cast<int>(dut.rootp->Top__DOT__outlinear__DOT__cu_inst__DOT__state)
                    << " cu_valid=" << static_cast<int>(dut.rootp->Top__DOT__outlinear__DOT__cu_inst__DOT__io_data_out_valid_REG_1)
                    << " su_valid=" << static_cast<int>(dut.rootp->Top__DOT__outlinear__DOT__su_inst__DOT__io_data_out_valid_REG)
                    << " feed_token=" << static_cast<int>(dut.rootp->Top__DOT__outlinear__DOT__feed_token_cnt)
                    << " feed_chunk=" << static_cast<int>(dut.rootp->Top__DOT__outlinear__DOT__feed_chunk_cnt)
                    << " w_lane0=" << static_cast<int>(
                           static_cast<int8_t>(dut.rootp->Top__DOT__outlinear__DOT__cu_inst__DOT____Vcellinp__mul_list_0_0__io_in1))
                    << " pipe2=" << hex_words(pipe_words, 3)
                    << " su0_hex=0x" << std::hex << su_words[0]
                    << " bias0_hex=0x" << bias_words[0] << std::dec
                    << " raw0=" << obs[0]
                    << " bias0=" << bias[0]
                    << " final0=" << final[0]
                    << " gold0=" << gold[0]
                    << std::endl;
          --outlinear_debug_left;
        }
        bool raw_match = true;
        for (std::size_t lane = 0; lane < 12; ++lane) {
          const float expected_raw = gold[lane] - bias[lane];
          const float abs_err = std::abs(obs[lane] - expected_raw);
          if (abs_err > 2.0e-3f) {
            raw_match = false;
            std::ostringstream oss;
            oss << "Top.outlinear_raw mismatch at beat=" << seen_outlinear_beats
                << " lane=" << lane
                << " observed=" << obs[lane]
                << " expected_raw=" << expected_raw
                << " abs_err=" << abs_err
                << " golden=" << gold[lane]
                << " bias=" << bias[lane];
            if (!allow_outlinear_raw_mismatch) {
              throw std::runtime_error(oss.str());
            }
            if (debug) {
              std::cerr << oss.str() << std::endl;
              std::cerr << "Top outlinear final probe"
                        << " beat=" << seen_outlinear_beats
                        << " final0=" << final[0]
                        << " gold0=" << gold[0]
                        << " final1=" << final[1]
                        << " gold1=" << gold[1]
                        << std::endl;
            }
            break;
          }
        }
        ++seen_outlinear_beats;
        if (seen_outlinear_beats == out_proj_golden.beats()) {
          checked_outlinear = true;
          if (debug) {
            std::cerr << (raw_match ? "Top outlinear raw matches staged golden-bias"
                                    : "Top outlinear raw mismatch tolerated")
                      << std::endl;
          }
        }
      }
      if (!checked_resadd1 && dut.rootp->Top__DOT__resadd__DOT__dm2_valid_r) {
        require(seen_resadd1_beats < resadd1_golden.beats(), "Top resadd1 produced too many beats");
        if (debug && debug_left > 0) {
          const auto orig = unpack_fp32_lanes(dut.rootp->Top__DOT__resadd__DOT___mem_io_r_data, 12);
          const auto dm2 = unpack_fp32_lanes(dut.rootp->Top__DOT__resadd__DOT__dm2_data_r, 12);
          const auto sum = unpack_fp32_lanes(dut.rootp->Top__DOT___resadd_io_res, 12);
          const auto gold = unpack_fp32_lanes(resadd1_golden.beat(seen_resadd1_beats), 12);
          std::cerr << "Top resadd1 probe"
                    << " beat=" << seen_resadd1_beats
                    << " out_st=" << static_cast<int>(dut.rootp->Top__DOT__outlinear__DOT__su_inst__DOT__io_data_out_st_REG)
                    << " out_last=" << static_cast<int>(dut.rootp->Top__DOT__outlinear__DOT__su_inst__DOT__io_data_out_last_REG)
                    << " out_addr=" << static_cast<int>(dut.rootp->Top__DOT__outlinear__DOT__su_inst__DOT__io_data_out_addr_REG)
                    << " dm2_st=" << static_cast<int>(dut.rootp->Top__DOT__resadd__DOT__dm2_st_r)
                    << " dm2_last=" << static_cast<int>(dut.rootp->Top__DOT__resadd__DOT__dm2_last_r)
                    << " orig0=" << orig[0]
                    << " dm2_0=" << dm2[0]
                    << " sum0=" << sum[0]
                    << " gold0=" << gold[0]
                    << std::endl;
        }
        if (debug && seen_resadd1_beats == 0) {
          const auto orig = unpack_fp32_lanes(dut.rootp->Top__DOT__resadd__DOT___mem_io_r_data, 12);
          const auto dm2 = unpack_fp32_lanes(dut.rootp->Top__DOT__resadd__DOT__dm2_data_r, 12);
          const auto sum = unpack_fp32_lanes(dut.rootp->Top__DOT___resadd_io_res, 12);
          const auto gold = unpack_fp32_lanes(resadd1_golden.beat(seen_resadd1_beats), 12);
          const auto out_gold = unpack_fp32_lanes(out_proj_golden.beat(seen_resadd1_beats), 12);
          auto beat_l1 = unpack_fp32_lanes(out_proj_golden.beat(1), 12);
          auto beat_l2 = unpack_fp32_lanes(out_proj_golden.beat(2), 12);
          const auto bank0_addr0 = unpack_fp32_lanes(
              dut.rootp->Top__DOT__resadd__DOT__mem__DOT__mem_list_0__DOT__mem_ext__DOT__Memory[0],
              12);
          const auto bank0_addr1 = unpack_fp32_lanes(
              dut.rootp->Top__DOT__resadd__DOT__mem__DOT__mem_list_0__DOT__mem_ext__DOT__Memory[1],
              12);
          std::cerr << "Top resadd1 first-beat"
                    << " out_addr=" << static_cast<int>(dut.rootp->Top__DOT__outlinear__DOT__su_inst__DOT__io_data_out_addr_REG)
                    << " read_addr=" << static_cast<int>(dut.rootp->Top__DOT__resadd__DOT__mem__DOT__mem_list_0__DOT__mem_ext__DOT___R0_addr_d0)
                    << " r_ptr=" << static_cast<int>(dut.rootp->Top__DOT__resadd__DOT__mem__DOT__r_ptr_r)
                    << " w_ptr=" << static_cast<int>(dut.rootp->Top__DOT__resadd__DOT__mem__DOT__w_ptr_r)
                    << " full_cnt=" << static_cast<int>(dut.rootp->Top__DOT__resadd__DOT__mem__DOT__full_cnt_r)
                    << " out_st=" << static_cast<int>(dut.rootp->Top__DOT__outlinear__DOT__su_inst__DOT__io_data_out_st_REG)
                    << " out_last=" << static_cast<int>(dut.rootp->Top__DOT__outlinear__DOT__su_inst__DOT__io_data_out_last_REG)
                    << " dm2_st=" << static_cast<int>(dut.rootp->Top__DOT__resadd__DOT__dm2_st_r)
                    << " dm2_last=" << static_cast<int>(dut.rootp->Top__DOT__resadd__DOT__dm2_last_r)
                    << " orig0=" << orig[0]
                    << " bank0_addr0=" << bank0_addr0[0]
                    << " bank0_addr1=" << bank0_addr1[0]
                    << " dm2_0=" << dm2[0]
                    << " out_gold0=" << out_gold[0]
                    << " out_gold1b0=" << beat_l1[0]
                    << " out_gold2b0=" << beat_l2[0]
                    << " sum0=" << sum[0]
                    << " gold0=" << gold[0]
                    << std::endl;
        }
        report_fp32_mismatch(
            "Top.resadd1",
            seen_resadd1_beats,
            dut.rootp->Top__DOT___resadd_io_res,
            resadd1_golden.beat(seen_resadd1_beats),
            12);
        ++seen_resadd1_beats;
        if (seen_resadd1_beats == resadd1_golden.beats()) {
          checked_resadd1 = true;
          if (debug) {
            std::cerr << "Top resadd1 matches staged golden" << std::endl;
          }
        }
      }
      if (!checked_ffnup && dut.rootp->Top__DOT__ffnup__DOT__su_inst__DOT__io_data_out_valid_REG) {
        require(seen_ffnup_beats < ffnup_golden.beats(), "Top ffnup produced too many beats");
        if (debug && !probed_ffnup_tolerated_diff) {
          const auto obs = unpack_int8_lanes(dut.rootp->Top__DOT___ffnup_io_data_out, 12);
          const auto exp = unpack_int8_lanes(ffnup_golden.beat(seen_ffnup_beats), 12);
          for (std::size_t lane = 0; lane < 12; ++lane) {
            if (obs[lane] != exp[lane]) {
              std::cerr << "Top ffnup tolerated diff"
                        << " beat=" << seen_ffnup_beats
                        << " lane=" << lane
                        << " observed=" << obs[lane]
                        << " expected=" << exp[lane]
                        << std::endl;
              probed_ffnup_tolerated_diff = true;
              break;
            }
          }
        }
        report_int8_mismatch(
            "Top.ffnup",
            seen_ffnup_beats,
            dut.rootp->Top__DOT___ffnup_io_data_out,
            ffnup_golden.beat(seen_ffnup_beats),
            3,
            12);
        ++seen_ffnup_beats;
        if (seen_ffnup_beats == ffnup_golden.beats()) {
          checked_ffnup = true;
          if (debug) {
            std::cerr << "Top ffnup matches staged golden" << std::endl;
          }
        }
      }
      if (!checked_ffndown_input && dut.rootp->Top__DOT__ffndown__DOT__mem_inst__DOT__full_cnt_r > 0) {
        for (std::size_t beat = 0; beat < ffnup_golden.beats(); ++beat) {
          report_int8_mismatch(
              "Top.ffndown_input",
              beat,
              dut.rootp->Top__DOT__ffndown__DOT__mem_inst__DOT__mem0_ext__DOT__Memory[beat],
              ffnup_golden.beat(beat),
              3,
              12);
        }
        checked_ffndown_input = true;
        if (debug) {
          std::cerr << "Top ffndown input mem0[0.." << (ffnup_golden.beats() - 1)
                    << "] matches staged fc2 input" << std::endl;
        }
      }
      if (checked_ffndown_input && seen_ffndown_input_beats < ffnup_golden.beats() &&
          dut.rootp->Top__DOT__ffndown__DOT__lu_inst__DOT__io_data_out_valid_REG) {
        report_int8_mismatch(
            "Top.ffndown_input_seq",
            seen_ffndown_input_beats,
            dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__indata_pipreg__DOT__pipData_2_REG,
            ffnup_golden.beat(seen_ffndown_input_beats),
            3,
            12);
        ++seen_ffndown_input_beats;
        if (debug && seen_ffndown_input_beats == ffnup_golden.beats()) {
          std::cerr << "Top ffndown consumed input sequence matches staged fc2 input" << std::endl;
        }
      }
      if (debug && seen_ffndown_writes < 8 &&
          dut.rootp->Top__DOT__ffnup__DOT__su_inst__DOT__io_data_out_valid_REG) {
        const auto write = unpack_int8_lanes(
            dut.rootp->Top__DOT__ffnup__DOT__su_inst__DOT__io_data_out_REG, 12);
        std::cerr << "Top ffndown write trace"
                  << " sample=" << seen_ffndown_writes
                  << " addr=" << static_cast<int>(dut.rootp->Top__DOT__ffnup__DOT__su_inst__DOT__io_data_out_addr_REG)
                  << " st=" << static_cast<int>(dut.rootp->Top__DOT__ffnup__DOT__su_inst__DOT__io_data_out_st_REG)
                  << " last=" << static_cast<int>(dut.rootp->Top__DOT__ffnup__DOT__su_inst__DOT__io_data_out_last_REG)
                  << " mem_busy=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__mem_inst__DOT__buzy_cnt_r)
                  << " mem_full=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__mem_inst__DOT__full_cnt_r)
                  << " w_sel=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__mem_inst__DOT__w_sel_r)
                  << " lane0=" << write[0]
                  << std::endl;
        ++seen_ffndown_writes;
      }
      if (debug && !probed_ffndown_write_last &&
          dut.rootp->Top__DOT__ffnup__DOT__su_inst__DOT__io_data_out_valid_REG &&
          dut.rootp->Top__DOT__ffnup__DOT__su_inst__DOT__io_data_out_last_REG) {
        std::cerr << "Top ffndown write-last"
                  << " addr=" << static_cast<int>(dut.rootp->Top__DOT__ffnup__DOT__su_inst__DOT__io_data_out_addr_REG)
                  << " st=" << static_cast<int>(dut.rootp->Top__DOT__ffnup__DOT__su_inst__DOT__io_data_out_st_REG)
                  << " mem_busy=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__mem_inst__DOT__buzy_cnt_r)
                  << " mem_full=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__mem_inst__DOT__full_cnt_r)
                  << " w_sel=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__mem_inst__DOT__w_sel_r)
                  << std::endl;
        probed_ffndown_write_last = true;
      }
      if (debug && seen_ffndown_lw_beats < 8 &&
          dut.rootp->Top__DOT__ffndown__DOT__lw_inst__DOT__io_data_out_valid_REG) {
        std::cerr << "Top ffndown lw trace"
                  << " sample=" << seen_ffndown_lw_beats
                  << " addr_reg=" << dut.rootp->Top__DOT__ffndown__DOT__lw_inst__DOT__addr_reg
                  << " state=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__lw_inst__DOT__state)
                  << " row_cnt=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__lw_inst__DOT__row_cnt_r)
                  << " rowblock_cnt=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__lw_inst__DOT__rowblock_cnt_r)
                  << " colblock_cnt=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__lw_inst__DOT__colblock_cnt_r)
                  << std::endl;
        ++seen_ffndown_lw_beats;
      }
      if (debug && checked_ffndown_input && seen_ffndown_runtime_weight_writes < 36 &&
          dut.rootp->Top__DOT__ffndown__DOT__lw_inst__DOT__io_data_out_valid_REG) {
        const auto beat = static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__lw_inst__DOT__addr_reg);
        const auto w0_row0 = find_exact_beat_words(ffndown_weight_init, dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w0_0_r);
        const auto w1_row0 = find_exact_beat_words(ffndown_weight_init, dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w1_0_r);
        std::cerr << "Top ffndown weight-write"
                  << " sample=" << seen_ffndown_runtime_weight_writes
                  << " beat=" << beat
                  << " w_w_sel=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__weight_w_sel)
                  << " w_w_cnt=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__weight_w_cnt)
                  << " w_r_sel=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__weight_r_sel)
                  << " w0_row0=" << (w0_row0 == static_cast<std::size_t>(-1) ? -1 : static_cast<int>(w0_row0))
                  << " w1_row0=" << (w1_row0 == static_cast<std::size_t>(-1) ? -1 : static_cast<int>(w1_row0))
                  << std::endl;
        ++seen_ffndown_runtime_weight_writes;
      }
      if (debug && checked_ffndown_input && seen_ffndown_mul_sources < 12) {
        const auto in_match = static_cast<int>(find_exact_beat_words(
            ffnup_golden,
            dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__indata_pipreg__DOT__pipData_2_REG));
        if (in_match >= 0 && in_match != prev_ffndown_mul_in_match) {
          const bool sel = dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__weight_r_sel;
          const auto row0_beat = sel
              ? find_exact_beat_words(ffndown_weight_init, dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w1_0_r)
              : find_exact_beat_words(ffndown_weight_init, dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w0_0_r);
          const auto row11_beat = sel
              ? find_exact_beat_words(ffndown_weight_init, dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w1_11_r)
              : find_exact_beat_words(ffndown_weight_init, dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w0_11_r);
          const auto w0_row0 = find_exact_beat_words(ffndown_weight_init, dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w0_0_r);
          const auto w1_row0 = find_exact_beat_words(ffndown_weight_init, dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w1_0_r);
          std::cerr << "Top ffndown mul-source"
                    << " sample=" << seen_ffndown_mul_sources
                    << " in_match=" << in_match
                    << " w_r_sel=" << static_cast<int>(sel)
                    << " w_w_sel=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__weight_w_sel)
                    << " row0_beat=" << (row0_beat == static_cast<std::size_t>(-1) ? -1 : static_cast<int>(row0_beat))
                    << " row11_beat=" << (row11_beat == static_cast<std::size_t>(-1) ? -1 : static_cast<int>(row11_beat))
                    << " w0_row0=" << (w0_row0 == static_cast<std::size_t>(-1) ? -1 : static_cast<int>(w0_row0))
                    << " w1_row0=" << (w1_row0 == static_cast<std::size_t>(-1) ? -1 : static_cast<int>(w1_row0))
                    << std::endl;
          prev_ffndown_mul_in_match = in_match;
          ++seen_ffndown_mul_sources;
        }
      }
      if (debug && checked_ffndown_input && seen_ffndown_psum_updates < 12) {
        const bool cur_sel = dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__weight_r_sel;
        const auto cur_in_match = find_exact_beat_words(
            ffnup_golden,
            dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__indata_pipreg__DOT__pipData_2_REG);
        const auto cur_row0 = cur_sel
            ? find_exact_beat_words(ffndown_weight_init, dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w1_0_r)
            : find_exact_beat_words(ffndown_weight_init, dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w0_0_r);
        const auto cur_row11 = cur_sel
            ? find_exact_beat_words(ffndown_weight_init, dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w1_11_r)
            : find_exact_beat_words(ffndown_weight_init, dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w0_11_r);
        const auto& in_words = dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__indata_pipreg__DOT__pipData_2_REG;
        const auto& row0_words = cur_sel
            ? dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w1_0_r
            : dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w0_0_r;
        const auto& row1_words = cur_sel
            ? dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w1_1_r
            : dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w0_1_r;
        const auto& row2_words = cur_sel
            ? dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w1_2_r
            : dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w0_2_r;
        const auto& row3_words = cur_sel
            ? dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w1_3_r
            : dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w0_3_r;
        const auto& row4_words = cur_sel
            ? dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w1_4_r
            : dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w0_4_r;
        const auto& row5_words = cur_sel
            ? dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w1_5_r
            : dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w0_5_r;
        const auto& row6_words = cur_sel
            ? dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w1_6_r
            : dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w0_6_r;
        const auto& row7_words = cur_sel
            ? dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w1_7_r
            : dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w0_7_r;
        const auto& row8_words = cur_sel
            ? dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w1_8_r
            : dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w0_8_r;
        const auto& row9_words = cur_sel
            ? dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w1_9_r
            : dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w0_9_r;
        const auto& row10_words = cur_sel
            ? dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w1_10_r
            : dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w0_10_r;
        const auto& row11_words = cur_sel
            ? dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w1_11_r
            : dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w0_11_r;
        const uint32_t* rows[] = {
            row0_words, row1_words, row2_words, row3_words, row4_words, row5_words,
            row6_words, row7_words, row8_words, row9_words, row10_words, row11_words};
        std::array<int, 12> in_lanes{};
        std::array<std::array<int, 12>, 4> w_cols{};
        std::array<std::array<int, 12>, 4> prod_cols{};
        std::array<int, 4> sum_cols{};
        for (int row = 0; row < 12; ++row) {
          in_lanes[row] = decode_i8_lane(in_words, row);
        }
        for (int col = 0; col < 4; ++col) {
          int sum = 0;
          for (int row = 0; row < 12; ++row) {
            const int in = in_lanes[row];
            const int w = decode_i8_lane(rows[11 - row], col);
            const int prod = in * w;
            w_cols[col][row] = w;
            prod_cols[col][row] = prod;
            sum += prod;
          }
          sum_cols[col] = sum;
        }
        ffndown_src_hist.push_back(FFNDownSourceEvent{cur_in_match, cur_sel, cur_row0, cur_row11, in_lanes, w_cols, prod_cols, sum_cols});
        if (ffndown_src_hist.size() > 6) {
          ffndown_src_hist.pop_front();
        }
        if (ffndown_src_hist.size() == 6) {
          const uint32_t cur0 = dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__psums0_0_0;
          const uint32_t cur1 = dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__psums0_0_1;
          const uint32_t cur2 = dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__psums0_0_2;
          const uint32_t cur3 = dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__psums0_0_3;
          if (cur0 != prev_ffndown_psum0 || cur1 != prev_ffndown_psum1 ||
              cur2 != prev_ffndown_psum2 || cur3 != prev_ffndown_psum3) {
            const auto& src = ffndown_src_hist.front();
            std::cerr << "Top ffndown psum trace"
                      << " sample=" << seen_ffndown_psum_updates
                      << " block_cnt=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__block_cnt_r)
                      << " batch_cnt=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__batchsize_cnt_r)
                      << " src_w_r_sel=" << static_cast<int>(src.w_r_sel)
                      << " src_in_match=" << (src.in_match == static_cast<std::size_t>(-1) ? -1 : static_cast<int>(src.in_match))
                      << " src_row0_beat=" << (src.row0_beat == static_cast<std::size_t>(-1) ? -1 : static_cast<int>(src.row0_beat))
                      << " src_row11_beat=" << (src.row11_beat == static_cast<std::size_t>(-1) ? -1 : static_cast<int>(src.row11_beat))
                      << " src_sum_cols={"
                      << src.sum_cols[0] << ","
                      << src.sum_cols[1] << ","
                      << src.sum_cols[2] << ","
                      << src.sum_cols[3] << "}"
                      << " addTree={"
                      << static_cast<int32_t>(dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT___addTreeList_0_io_out) << ","
                      << static_cast<int32_t>(dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT___addTreeList_1_io_out) << ","
                      << static_cast<int32_t>(dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT___addTreeList_2_io_out) << ","
                      << static_cast<int32_t>(dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT___addTreeList_3_io_out) << "}"
                      << " psums0={"
                      << static_cast<int32_t>(cur0) << ","
                      << static_cast<int32_t>(cur1) << ","
                      << static_cast<int32_t>(cur2) << ","
                      << static_cast<int32_t>(cur3) << "}"
                      << std::endl;
            if (static_cast<int>(seen_ffndown_psum_updates) == ffndown_detail_sample) {
              std::cerr << "Top ffndown psum detail"
                        << " sample=" << seen_ffndown_psum_updates
                        << " src_in={";
              for (int row = 0; row < 12; ++row) {
                if (row) std::cerr << ",";
                std::cerr << src.in_lanes[row];
              }
              std::cerr << "}";
              for (int col = 0; col < 4; ++col) {
                std::ostringstream woss;
                std::ostringstream poss;
                for (int row = 0; row < 12; ++row) {
                  if (row) {
                    woss << ",";
                    poss << ",";
                  }
                  woss << src.w_cols[col][row];
                  poss << src.prod_cols[col][row];
                }
                std::cerr << " w_col" << col << "={" << woss.str() << "}"
                          << " prod_col" << col << "={" << poss.str() << "}"
                          << " sum_col" << col << "=" << src.sum_cols[col];
              }
              std::cerr << std::endl;
            }
            prev_ffndown_psum0 = cur0;
            prev_ffndown_psum1 = cur1;
            prev_ffndown_psum2 = cur2;
            prev_ffndown_psum3 = cur3;
            ++seen_ffndown_psum_updates;
          }
        }
      }
      if (debug && !probed_ffndown_su_first &&
          dut.rootp->Top__DOT__ffndown__DOT__su_inst__DOT__io_data_out_valid_REG) {
        ffndown_su_first_raw = unpack_fp32_lanes(dut.rootp->Top__DOT__ffndown__DOT__su_inst__DOT__io_data_out_REG, 12);
        ffndown_su_first_addr = static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__su_inst__DOT__io_data_out_addr_REG);
        ffndown_su_first_st = static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__su_inst__DOT__io_data_out_st_REG);
        const uint32_t* first_bias_words = nullptr;
        switch (ffndown_su_first_addr) {
          case 0: first_bias_words = dut.rootp->Top__DOT__ffndown__DOT__bias_mem_0; break;
          case 1: first_bias_words = dut.rootp->Top__DOT__ffndown__DOT__bias_mem_1; break;
          case 2: first_bias_words = dut.rootp->Top__DOT__ffndown__DOT__bias_mem_2; break;
          case 3: first_bias_words = dut.rootp->Top__DOT__ffndown__DOT__bias_mem_3; break;
          default: break;
        }
        if (first_bias_words != nullptr) {
          ffndown_su_first_bias = unpack_fp32_lanes(first_bias_words, 12);
        } else {
          ffndown_su_first_bias.assign(12, 0.0f);
        }
        std::cerr << "Top ffndown su-first"
                  << " su_addr=" << ffndown_su_first_addr
                  << " su_st=" << static_cast<int>(ffndown_su_first_st)
                  << " su_last=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__su_inst__DOT__io_data_out_last_REG)
                  << " cu_valid=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__io_data_out_valid_REG)
                  << " res_vec=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__res_vector_cnt_r)
                  << " out_bank=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__out_bank_sel)
                  << " raw0=" << ffndown_su_first_raw[0]
                  << " bias0=" << ffndown_su_first_bias[0]
                  << std::endl;
        probed_ffndown_su_first = true;
      }
      const int ffndown_cu_state = static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__state);
      if (debug && !probed_ffndown_cu_start &&
          prev_ffndown_cu_state == 0 && ffndown_cu_state == 1) {
        const auto in_match = find_exact_beat_words(ffnup_golden, dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__indata_pipreg__DOT__pipData_2_REG);
        const auto w0_row0 = find_exact_beat_words(ffndown_weight_init, dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w0_0_r);
        const auto w1_row0 = find_exact_beat_words(ffndown_weight_init, dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w1_0_r);
        const bool sel = dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__weight_r_sel;
        const auto row0_beat = sel
            ? find_exact_beat_words(ffndown_weight_init, dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w1_0_r)
            : find_exact_beat_words(ffndown_weight_init, dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w0_0_r);
        const auto row11_beat = sel
            ? find_exact_beat_words(ffndown_weight_init, dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w1_11_r)
            : find_exact_beat_words(ffndown_weight_init, dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__w0_11_r);
        std::cerr << "Top ffndown cu-start"
                  << " psum_sel=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__psum_sel_r)
                  << " res_batch_cnt=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__res_batchsize_cnt_r)
                  << " res_vec_cnt=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__res_vector_cnt_r)
                  << " block_cnt=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__block_cnt_r)
                  << " in_match=" << (in_match == static_cast<std::size_t>(-1) ? -1 : static_cast<int>(in_match))
                  << " w_r_sel=" << static_cast<int>(sel)
                  << " row0_beat=" << (row0_beat == static_cast<std::size_t>(-1) ? -1 : static_cast<int>(row0_beat))
                  << " row11_beat=" << (row11_beat == static_cast<std::size_t>(-1) ? -1 : static_cast<int>(row11_beat))
                  << " w0_row0=" << (w0_row0 == static_cast<std::size_t>(-1) ? -1 : static_cast<int>(w0_row0))
                  << " w1_row0=" << (w1_row0 == static_cast<std::size_t>(-1) ? -1 : static_cast<int>(w1_row0))
                  << " psums0_0_0=0x" << std::hex << dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__psums0_0_0
                  << " psums1_0_0=0x" << dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__psums1_0_0
                  << std::dec
                  << std::endl;
        probed_ffndown_cu_start = true;
      }
      prev_ffndown_cu_state = ffndown_cu_state;
      if (!checked_ffndown && dut.rootp->Top__DOT__resadd2__DOT__ffn_valid_r) {
        require(seen_ffndown_beats < ffndown_golden.beats(), "Top ffndown produced too many beats");
        if (debug && seen_ffndown_beats < 4) {
          const auto raw = unpack_fp32_lanes(dut.rootp->Top__DOT__ffndown__DOT__su_inst__DOT__io_data_out_REG, 12);
          const auto final = unpack_fp32_lanes(dut.rootp->Top__DOT__resadd2__DOT__ffn_data_r, 12);
          const auto gold = unpack_fp32_lanes(ffndown_golden.beat(seen_ffndown_beats), 12);
          const auto addr = dut.rootp->Top__DOT__ffndown__DOT__su_inst__DOT__io_data_out_addr_REG;
          const uint32_t* bias_words = nullptr;
          switch (addr) {
            case 0: bias_words = dut.rootp->Top__DOT__ffndown__DOT__bias_mem_0; break;
            case 1: bias_words = dut.rootp->Top__DOT__ffndown__DOT__bias_mem_1; break;
            case 2: bias_words = dut.rootp->Top__DOT__ffndown__DOT__bias_mem_2; break;
            case 3: bias_words = dut.rootp->Top__DOT__ffndown__DOT__bias_mem_3; break;
            default: break;
          }
          std::vector<float> bias(12, 0.0f);
          if (bias_words != nullptr) {
            bias = unpack_fp32_lanes(bias_words, 12);
          }
          std::cerr << "Top ffndown probe"
                    << " beat=" << seen_ffndown_beats
                    << " addr=" << addr
                    << " su_first_addr=" << ffndown_su_first_addr
                    << " su_first_st=" << static_cast<int>(ffndown_su_first_st)
                    << " su_first_raw0=" << (ffndown_su_first_raw.empty() ? 0.0f : ffndown_su_first_raw[0])
                    << " su_first_bias0=" << (ffndown_su_first_bias.empty() ? 0.0f : ffndown_su_first_bias[0])
                    << " ffn_st_r=" << static_cast<int>(dut.rootp->Top__DOT__resadd2__DOT__ffn_st_r)
                    << " ffn_last_r=" << static_cast<int>(dut.rootp->Top__DOT__resadd2__DOT__ffn_last_r)
                    << " top_res_addr=" << static_cast<int>(dut.io_res_addr)
                    << " top_res_st=" << static_cast<int>(dut.io_res_st)
                    << " lu_state=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__lu_inst__DOT__state)
                    << " lu_valid=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__lu_inst__DOT__io_data_out_valid_REG)
                    << " cu_valid=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__io_data_out_valid_REG)
                    << " su_valid=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__su_inst__DOT__io_data_out_valid_REG)
                    << " out_bank=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__out_bank_sel)
                    << " res_vec=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__res_vector_cnt_r)
                    << " toFp0=" << static_cast<int32_t>(dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT____Vcellinp__toFp__io_in[0])
                    << " raw0=" << raw[0]
                    << " bias0=" << bias[0]
                    << " final0=" << final[0]
                    << " gold0=" << gold[0]
                    << std::endl;
        }
        report_fp32_mismatch(
            "Top.ffndown",
            seen_ffndown_beats,
            dut.rootp->Top__DOT__resadd2__DOT__ffn_data_r,
            ffndown_golden.beat(seen_ffndown_beats),
            12);
        ++seen_ffndown_beats;
        if (seen_ffndown_beats == ffndown_golden.beats()) {
          checked_ffndown = true;
          if (debug) {
            std::cerr << "Top ffndown matches staged golden" << std::endl;
          }
        }
      }
      if (debug && cycle > 0 && (cycle % 1000) == 0) {
        std::cerr << "Top progress"
                  << " cycle=" << cycle
                  << " ln_state=" << static_cast<int>(dut.rootp->Top__DOT__ln_addr_gen__DOT__state)
                  << " ln_valid=" << static_cast<int>(dut.rootp->Top__DOT__ln_addr_gen__DOT__io_data_valid_REG)
                  << " mem_addr=" << dut.io_data_in_addr
                  << " qkv_state=" << static_cast<int>(dut.rootp->Top__DOT__qkvlinear__DOT__state)
                  << " qkv_valid=" << static_cast<int>(dut.rootp->Top__DOT___qkvlinear_io_data_out_valid)
                  << " qkv_buf0_checked=" << static_cast<int>(checked_qkv_buf0)
                  << " qkv_head=" << static_cast<int>(dut.rootp->Top__DOT__qkvlinear__DOT__head_cnt_r)
                  << " atten_ready=" << static_cast<int>(dut.rootp->Top__DOT___atten_io_data_ready)
                  << " dm1_checked=" << seen_dm1_beats
                  << " sm_lw_state=" << static_cast<int>(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__lwInst__DOT__state)
                  << " sm_lw_valid=" << static_cast<int>(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__lwInst__DOT__io_data_out_valid_REG)
                  << " softmax_checked=" << seen_softmax_beats
                  << " sm_mask0=" << static_cast<int>(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__cuInst__DOT____Vcellinp__softmax__io_in_mask_0)
                  << " sm_any=" << static_cast<int>(dut.rootp->Top__DOT__atten__DOT__softmax__DOT__cuInst__DOT__softmax__DOT__anyValid)
                  << " dm2_checked=" << seen_dm2_beats
                  << " out_state=" << static_cast<int>(dut.rootp->Top__DOT__outlinear__DOT__state)
                  << " headbuf0_checked=" << static_cast<int>(checked_headbuf0)
                  << " out_checked=" << seen_outlinear_beats
                  << " resadd_state=" << static_cast<int>(dut.rootp->Top__DOT__resadd__DOT__state)
                  << " resadd_checked=" << seen_resadd1_beats
                  << " ffnup_checked=" << seen_ffnup_beats
                  << " ffndown_checked=" << seen_ffndown_beats
                  << " ffndown_lu_valid=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__lu_inst__DOT__io_data_out_valid_REG)
                  << " ffndown_cu_valid=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__cu_inst__DOT__io_data_out_valid_REG)
                  << " ffndown_su_valid=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__su_inst__DOT__io_data_out_valid_REG)
                  << " ffndown_lu_state=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__lu_inst__DOT__state)
                  << " ffndown_mem_full=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__mem_inst__DOT__full_cnt_r)
                  << " ffndown_mem_busy=" << static_cast<int>(dut.rootp->Top__DOT__ffndown__DOT__mem_inst__DOT__buzy_cnt_r)
                  << " ffnup_su_valid=" << static_cast<int>(dut.rootp->Top__DOT__ffnup__DOT__su_inst__DOT__io_data_out_valid_REG)
                  << " res_valid=" << static_cast<int>(dut.io_res_valid)
                  << std::endl;
      }
      if (debug && resadd2_probe_beat >= 0 && dut.io_res_valid &&
          static_cast<int>(dut.io_res_addr) == resadd2_probe_beat) {
        const auto s7 = unpack_fp32_lanes(dut.rootp->Top__DOT__resadd2__DOT___mem_io_r_data, 12);
        const auto ffn = unpack_fp32_lanes(dut.rootp->Top__DOT__resadd2__DOT__ffn_data_r, 12);
        const auto res = unpack_fp32_lanes(dut.io_res, 12);
        const auto gold = unpack_fp32_lanes(golden.beat(dut.io_res_addr), 12);
        std::cerr << "Top resadd2 probe"
                  << " beat=" << static_cast<int>(dut.io_res_addr)
                  << " st=" << static_cast<int>(dut.io_res_st)
                  << " last=" << static_cast<int>(dut.io_res_last)
                  << " ffn_st_r=" << static_cast<int>(dut.rootp->Top__DOT__resadd2__DOT__ffn_st_r)
                  << " ffn_last_r=" << static_cast<int>(dut.rootp->Top__DOT__resadd2__DOT__ffn_last_r)
                  << " s7_0=" << s7[0]
                  << " ffn_0=" << ffn[0]
                  << " res_0=" << res[0]
                  << " gold_0=" << gold[0]
                  << " s7_2=" << s7[2]
                  << " ffn_2=" << ffn[2]
                  << " res_2=" << res[2]
                  << " gold_2=" << gold[2]
                  << std::endl;
      }
      if (!dut.io_res_valid) {
        continue;
      }
      const std::size_t addr = dut.io_res_addr;
      require(addr < golden.beats(), "Top output addr out of range");
      copy_words(observed.data() + addr * 12, dut.io_res, 12);
      seen[addr] = true;
      saw_st = saw_st || dut.io_res_st;
      saw_last = saw_last || dut.io_res_last;
      if (debug && debug_left > 0) {
        std::cerr << "Top output"
                  << " cycle=" << cycle
                  << " addr=" << addr
                  << " st=" << static_cast<int>(dut.io_res_st)
                  << " last=" << static_cast<int>(dut.io_res_last)
                  << " words=" << hex_words(dut.io_res, 12)
                  << std::endl;
        --debug_left;
      }
    }

    require(all_seen(seen), "missing Top output beats");
    require(saw_st, "Top never asserted io_res_st");
    require(saw_last, "Top never asserted io_res_last");
    for (std::size_t beat = 0; beat < golden.beats(); ++beat) {
      report_fp32_mismatch("Top", beat, observed.data() + beat * 12, golden.beat(beat), 12);
    }
    std::cout << "Top PASS beats=" << golden.beats() << std::endl;
    return 0;
  } catch (const std::exception& ex) {
    std::cerr << ex.what() << std::endl;
    return 1;
  }
}
