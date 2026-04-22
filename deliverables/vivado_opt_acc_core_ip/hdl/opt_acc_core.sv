module opt_acc_core #(
  parameter integer CORE_EACH_SLR = 2,
  parameter [31:0] ADDR_OFFSET_H = 32'h00000008,
  parameter [31:0] ADDR_OFFSET_L = 32'h00000000,
  parameter integer C_DATA_WIDTH = 512,
  parameter integer CORE_NUM_SHARE_AXI = 2,
  parameter integer NUM_LAYERS = 12
)(
  input         clk_300M,
  input         clk_600M,
  input  [703:0] cfg_data,
  input         cfg_data_valid,
  input         cfg_done,
  input         cnn0_input_batch_set,
  output [2:0]  cnn0_batch_count,
  input         cnn0_result_batch_clear,
  output [2:0]  cnn0_result_count,
  input         user_rst,
  output reg [3:0]   c0_ddr4_s_axi_awid,
  output reg [36:0]  c0_ddr4_s_axi_awaddr,
  output reg [7:0]   c0_ddr4_s_axi_awlen,
  output reg [2:0]   c0_ddr4_s_axi_awsize,
  output reg [1:0]   c0_ddr4_s_axi_awburst,
  output reg         c0_ddr4_s_axi_awlock,
  output reg [3:0]   c0_ddr4_s_axi_awcache,
  output reg [2:0]   c0_ddr4_s_axi_awprot,
  output reg         c0_ddr4_s_axi_awvalid,
  input              c0_ddr4_s_axi_awready,
  output reg [511:0] c0_ddr4_s_axi_wdata,
  output reg [63:0]  c0_ddr4_s_axi_wstrb,
  output reg         c0_ddr4_s_axi_wlast,
  output reg         c0_ddr4_s_axi_wvalid,
  input              c0_ddr4_s_axi_wready,
  output reg         c0_ddr4_s_axi_bready,
  input      [3:0]   c0_ddr4_s_axi_bid,
  input      [1:0]   c0_ddr4_s_axi_bresp,
  input              c0_ddr4_s_axi_bvalid,
  output reg [3:0]   c0_ddr4_s_axi_arid,
  output reg [36:0]  c0_ddr4_s_axi_araddr,
  output reg [7:0]   c0_ddr4_s_axi_arlen,
  output reg [2:0]   c0_ddr4_s_axi_arsize,
  output reg [1:0]   c0_ddr4_s_axi_arburst,
  output reg         c0_ddr4_s_axi_arlock,
  output reg [3:0]   c0_ddr4_s_axi_arcache,
  output reg [2:0]   c0_ddr4_s_axi_arprot,
  output reg         c0_ddr4_s_axi_arvalid,
  input              c0_ddr4_s_axi_arready,
  output reg         c0_ddr4_s_axi_rready,
  input              c0_ddr4_s_axi_rlast,
  input              c0_ddr4_s_axi_rvalid,
  input      [1:0]   c0_ddr4_s_axi_rresp,
  input      [3:0]   c0_ddr4_s_axi_rid,
  input      [511:0] c0_ddr4_s_axi_rdata,
  input              c0_ddr4_s_axi_clk,
  input              c0_ddr4_s_axi_rst_n,
  input              c0_init_calib_complete,
  input              sys_rst_n
);

  localparam integer LN_WEIGHT_BEATS = 128;
  localparam integer QKV_WEIGHT_BEATS = 49152;
  localparam integer QKV_BIAS_BEATS = 192;
  localparam integer SM_BEATS = 26;
  localparam integer SOFTMAX_SEQ_LEN = 16;
  localparam integer OUT_WEIGHT_BEATS = 16896;
  localparam integer OUT_BIAS_BEATS = 64;
  localparam integer FFNUP_WEIGHT_BEATS = 66048;
  localparam integer FFNUP_BIAS_BEATS = 256;
  localparam integer FFNDOWN_WEIGHT_BEATS = 67584;
  localparam integer FFNDOWN_BIAS_BEATS = 64;
  localparam integer LAYER_SCALAR_BEATS = 2;
  localparam integer LAYER_REGION_BEATS =
      LAYER_SCALAR_BEATS + LN_WEIGHT_BEATS + QKV_WEIGHT_BEATS + QKV_BIAS_BEATS + SM_BEATS +
      OUT_WEIGHT_BEATS + OUT_BIAS_BEATS + LN_WEIGHT_BEATS + FFNUP_WEIGHT_BEATS +
      FFNUP_BIAS_BEATS + FFNDOWN_WEIGHT_BEATS + FFNDOWN_BIAS_BEATS;
  localparam integer LAYER_LN1_W_OFF_BEATS = LAYER_SCALAR_BEATS;
  localparam integer LAYER_QKV_W_OFF_BEATS = LAYER_LN1_W_OFF_BEATS + LN_WEIGHT_BEATS;
  localparam integer LAYER_QKV_B_OFF_BEATS = LAYER_QKV_W_OFF_BEATS + QKV_WEIGHT_BEATS;
  localparam integer LAYER_SM_OFF_BEATS = LAYER_QKV_B_OFF_BEATS + QKV_BIAS_BEATS;
  localparam integer LAYER_OUT_W_OFF_BEATS = LAYER_SM_OFF_BEATS + SM_BEATS;
  localparam integer LAYER_OUT_B_OFF_BEATS = LAYER_OUT_W_OFF_BEATS + OUT_WEIGHT_BEATS;
  localparam integer LAYER_LN2_W_OFF_BEATS = LAYER_OUT_B_OFF_BEATS + OUT_BIAS_BEATS;
  localparam integer LAYER_FFNUP_W_OFF_BEATS = LAYER_LN2_W_OFF_BEATS + LN_WEIGHT_BEATS;
  localparam integer LAYER_FFNUP_B_OFF_BEATS = LAYER_FFNUP_W_OFF_BEATS + FFNUP_WEIGHT_BEATS;
  localparam integer LAYER_FFNDOWN_W_OFF_BEATS = LAYER_FFNUP_B_OFF_BEATS + FFNUP_BIAS_BEATS;
  localparam integer LAYER_FFNDOWN_B_OFF_BEATS = LAYER_FFNDOWN_W_OFF_BEATS + FFNDOWN_WEIGHT_BEATS;
  localparam integer TOKEN_BEATS = 64;
  localparam integer AXI_BEAT_BYTES = 64;
  localparam integer SHORT_SEQ_MAX_TOKENS = 16;
  localparam integer SHORT_SEQ_TOTAL_BEATS = SHORT_SEQ_MAX_TOKENS * TOKEN_BEATS;
  localparam integer TOKEN_SLOT_COUNT = 4;
  localparam integer TOKEN_SLOT_BITS = 2;

  localparam [31:0] OFF_INPUT_BEATS     = 32'd0;
  localparam [31:0] OFF_LN1_W_BEATS     = 32'd58368;
  localparam [31:0] OFF_QKV_W_BEATS     = 32'd58496;
  localparam [31:0] OFF_QKV_B_BEATS     = 32'd107648;
  localparam [31:0] OFF_SM_BEATS        = 32'd107840;
  localparam [31:0] OFF_OUT_W_BEATS     = 32'd107866;
  localparam [31:0] OFF_OUT_B_BEATS     = 32'd124762;
  localparam [31:0] OFF_LN2_W_BEATS     = 32'd124826;
  localparam [31:0] OFF_FFNUP_W_BEATS   = 32'd124954;
  localparam [31:0] OFF_FFNUP_B_BEATS   = 32'd191002;
  localparam [31:0] OFF_FFNDOWN_W_BEATS = 32'd191258;
  localparam [31:0] OFF_FFNDOWN_B_BEATS = 32'd258842;

  localparam [4:0]
    ST_IDLE             = 5'd0,
    ST_LOAD_LN1         = 5'd1,
    ST_LOAD_QKV_W       = 5'd2,
    ST_LOAD_QKV_B       = 5'd3,
    ST_LOAD_SM          = 5'd4,
    ST_LOAD_OUT_W       = 5'd5,
    ST_LOAD_OUT_B       = 5'd6,
    ST_LOAD_LN2         = 5'd7,
    ST_LOAD_FFNUP_W     = 5'd8,
    ST_LOAD_FFNUP_B     = 5'd9,
    ST_LOAD_FFNDOWN_W   = 5'd10,
    ST_LOAD_FFNDOWN_B   = 5'd11,
    ST_CORE_RESET       = 5'd12,
    ST_STREAM_LN1       = 5'd13,
    ST_STREAM_LN2       = 5'd14,
    ST_STREAM_QKV_B     = 5'd15,
    ST_STREAM_OUT_B     = 5'd16,
    ST_STREAM_FFNUP_B   = 5'd17,
    ST_STREAM_FFNDOWN_B = 5'd18,
    ST_LOAD_TOKEN       = 5'd19,
    ST_RUN_CFG          = 5'd20,
    ST_PRELOAD_PULSE    = 5'd21,
    ST_PRELOAD_WAIT     = 5'd22,
    ST_RUN              = 5'd23,
    ST_WRITEBACK        = 5'd24,
    ST_DONE             = 5'd25,
    ST_KVHIST_PREP      = 5'd26,
    ST_KVHIST_WRITE     = 5'd27,
    ST_REPLAY_PREP      = 5'd28,
    ST_REPLAY_READ      = 5'd29,
    ST_REPLAY_STREAM    = 5'd30,
    ST_PIPE_SCHED       = 5'd31;

  wire clock = c0_ddr4_s_axi_clk;
  wire reset = user_rst | ~sys_rst_n | ~c0_ddr4_s_axi_rst_n;

  reg [703:0] cfg_shadow;
  reg [703:0] cfg_active;
  reg         cfg_loaded;
  wire [31:0] cfg_words [0:21];
  genvar cfg_i;
  generate
    for (cfg_i = 0; cfg_i < 22; cfg_i = cfg_i + 1) begin : cfg_unpack
      assign cfg_words[cfg_i] = cfg_active[cfg_i * 32 +: 32];
    end
  endgenerate

  wire [63:0] axi_window_offset = {ADDR_OFFSET_H, ADDR_OFFSET_L};
  wire [15:0] cfg_seqlen_word = cfg_words[0][15:0];
  wire [31:0] output_stride_bytes = cfg_words[1];
  wire [63:0] window_rel_addr = {cfg_words[3], cfg_words[2]};
  wire [63:0] output_rel_addr = {cfg_words[5], cfg_words[4]};
  wire [63:0] window_base_addr = axi_window_offset + window_rel_addr;
  wire [63:0] output_base_addr = axi_window_offset + output_rel_addr;
  wire [7:0]  ln1_zero_point = cfg_words[6][7:0];
  wire [7:0]  dm2_ctx_zero_point = cfg_words[6][15:8];
  wire [7:0]  ln2_zero_point = cfg_words[6][23:16];
  wire        all_layers_mode = cfg_words[6][24];

  wire [31:0] ln1_out_inv_scale = cfg_words[7];
  wire [31:0] q_out_inv_scale = cfg_words[8];
  wire [31:0] k_out_inv_scale = cfg_words[9];
  wire [31:0] v_out_inv_scale = cfg_words[10];
  wire [31:0] q_bias_scale = cfg_words[11];
  wire [31:0] k_bias_scale = cfg_words[12];
  wire [31:0] v_bias_scale = cfg_words[13];
  wire [31:0] dm1_out_scale = cfg_words[14];
  wire [31:0] dm2_ctx_inv_scale = cfg_words[15];
  wire [31:0] dm2_out_inv_scale = cfg_words[16];
  wire [31:0] out_out_scale = cfg_words[17];
  wire [31:0] ln2_out_inv_scale = cfg_words[18];
  wire [31:0] ffnup_out_inv_scale = cfg_words[19];
  wire [31:0] ffnup_bias_scale = cfg_words[20];
  wire [31:0] ffndown_out_scale = cfg_words[21];

  wire [63:0] input_base_addr     = window_base_addr + ({32'd0, OFF_INPUT_BEATS}     << 6);
  wire [63:0] ln1_w_base_addr     = window_base_addr + ({32'd0, OFF_LN1_W_BEATS}     << 6);
  wire [63:0] qkv_w_base_addr     = window_base_addr + ({32'd0, OFF_QKV_W_BEATS}     << 6);
  wire [63:0] qkv_b_base_addr     = window_base_addr + ({32'd0, OFF_QKV_B_BEATS}     << 6);
  wire [63:0] sm_base_addr        = window_base_addr + ({32'd0, OFF_SM_BEATS}        << 6);
  wire [63:0] out_w_base_addr     = window_base_addr + ({32'd0, OFF_OUT_W_BEATS}     << 6);
  wire [63:0] out_b_base_addr     = window_base_addr + ({32'd0, OFF_OUT_B_BEATS}     << 6);
  wire [63:0] ln2_w_base_addr     = window_base_addr + ({32'd0, OFF_LN2_W_BEATS}     << 6);
  wire [63:0] ffnup_w_base_addr   = window_base_addr + ({32'd0, OFF_FFNUP_W_BEATS}   << 6);
  wire [63:0] ffnup_b_base_addr   = window_base_addr + ({32'd0, OFF_FFNUP_B_BEATS}   << 6);
  wire [63:0] ffndown_w_base_addr = window_base_addr + ({32'd0, OFF_FFNDOWN_W_BEATS} << 6);
  wire [63:0] ffndown_b_base_addr = window_base_addr + ({32'd0, OFF_FFNDOWN_B_BEATS} << 6);

  reg [4:0] state;
  reg [31:0] issue_count;
  reg [31:0] recv_count;
  reg [7:0]  core_reset_cnt;
  reg [7:0]  preload_wait_cnt;
  reg        preload_full_wait;
  reg [31:0] stream_cnt;
  reg [15:0] run_token_idx;
  reg [3:0]  layer_idx;
  reg        active_weight_bank;
  reg        active_act_bank;
  reg        weight_init_bank_sel;
  reg        token_last_pending;
  reg        token_res_started;
  reg [10:0] token_last_core_addr;
  reg [10:0] writeback_idx;
  reg        result_done;
  reg        rd_active;
  reg [3:0]  preload_layer_idx;
  reg [3:0]  pending_layer_idx;
  reg        pending_layer_switch;
  reg [3:0]  weight_bank_layer_idx [0:1];
  reg [1:0]  weight_bank_valid;
  reg        bg_preload_active;
  reg        bg_preload_bank_sel;
  reg [3:0]  bg_preload_layer_idx;
  reg [4:0]  bg_preload_state;
  reg [31:0] bg_issue_count;
  reg [31:0] bg_recv_count;
  reg        rd_bg_preload_active;

  reg [383:0] act_buf [0:1][0:SHORT_SEQ_TOTAL_BEATS-1];
  reg        short_inputs_loaded;

  reg [383:0] ln1_w_mem [0:1][0:LN_WEIGHT_BEATS-1];
  reg [383:0] ln2_w_mem [0:1][0:LN_WEIGHT_BEATS-1];
  reg [95:0]  qkv_b_mem [0:1][0:QKV_BIAS_BEATS-1];
  reg [SOFTMAX_SEQ_LEN-1:0]  sm_w_mem [0:SM_BEATS-1];
  reg [383:0] out_b_mem [0:1][0:OUT_BIAS_BEATS-1];
  reg [95:0]  ffnup_b_mem [0:1][0:FFNUP_BIAS_BEATS-1];
  reg [383:0] ffndown_b_mem [0:1][0:FFNDOWN_BIAS_BEATS-1];

  reg [511:0] writeback_buf;
  reg [63:0]  cur_base_addr;
  reg [31:0]  cur_len;

  wire rd_fire = rd_active && c0_ddr4_s_axi_rvalid && c0_ddr4_s_axi_rready;

  wire [63:0] total_tokens_u64 = {48'd0, cfg_seqlen_word} + 64'd1;
  wire [63:0] total_output_beats_u64 = total_tokens_u64 << 6;
  wire [63:0] layers_base_addr = input_base_addr + (total_output_beats_u64 << 6);
  wire short_seq_all_layers_mode = all_layers_mode;
  wire [10:0] short_total_beats = ({6'd0, cfg_seqlen_word[4:0]} + 11'd1) << 6;
  wire [63:0] preload_layer_base_addr =
      layers_base_addr + ({60'd0, preload_layer_idx} * (64'd200540 << 6));
  wire [63:0] bg_preload_layer_base_addr =
      layers_base_addr + ({60'd0, bg_preload_layer_idx} * (64'd200540 << 6));
  wire [63:0] ln1_w_load_base_addr =
      short_seq_all_layers_mode ? (preload_layer_base_addr + (64'd2 << 6)) : ln1_w_base_addr;
  wire [63:0] qkv_w_load_base_addr =
      short_seq_all_layers_mode ? (preload_layer_base_addr + (64'd130 << 6)) : qkv_w_base_addr;
  wire [63:0] qkv_b_load_base_addr =
      short_seq_all_layers_mode ? (preload_layer_base_addr + (64'd49282 << 6)) : qkv_b_base_addr;
  wire [63:0] sm_load_base_addr =
      short_seq_all_layers_mode ? (preload_layer_base_addr + (64'd49474 << 6)) : sm_base_addr;
  wire [63:0] out_w_load_base_addr =
      short_seq_all_layers_mode ? (preload_layer_base_addr + (64'd49500 << 6)) : out_w_base_addr;
  wire [63:0] out_b_load_base_addr =
      short_seq_all_layers_mode ? (preload_layer_base_addr + (64'd66396 << 6)) : out_b_base_addr;
  wire [63:0] ln2_w_load_base_addr =
      short_seq_all_layers_mode ? (preload_layer_base_addr + (64'd66460 << 6)) : ln2_w_base_addr;
  wire [63:0] ffnup_w_load_base_addr =
      short_seq_all_layers_mode ? (preload_layer_base_addr + (64'd66588 << 6)) : ffnup_w_base_addr;
  wire [63:0] ffnup_b_load_base_addr =
      short_seq_all_layers_mode ? (preload_layer_base_addr + (64'd132636 << 6)) : ffnup_b_base_addr;
  wire [63:0] ffndown_w_load_base_addr =
      short_seq_all_layers_mode ? (preload_layer_base_addr + (64'd132892 << 6)) : ffndown_w_base_addr;
  wire [63:0] ffndown_b_load_base_addr =
      short_seq_all_layers_mode ? (preload_layer_base_addr + (64'd200476 << 6)) : ffndown_b_base_addr;
  wire [63:0] bg_ln1_w_load_base_addr = bg_preload_layer_base_addr + (64'd2 << 6);
  wire [63:0] bg_qkv_w_load_base_addr = bg_preload_layer_base_addr + (64'd130 << 6);
  wire [63:0] bg_qkv_b_load_base_addr = bg_preload_layer_base_addr + (64'd49282 << 6);
  wire [63:0] bg_sm_load_base_addr = bg_preload_layer_base_addr + (64'd49474 << 6);
  wire [63:0] bg_out_w_load_base_addr = bg_preload_layer_base_addr + (64'd49500 << 6);
  wire [63:0] bg_out_b_load_base_addr = bg_preload_layer_base_addr + (64'd66396 << 6);
  wire [63:0] bg_ln2_w_load_base_addr = bg_preload_layer_base_addr + (64'd66460 << 6);
  wire [63:0] bg_ffnup_w_load_base_addr = bg_preload_layer_base_addr + (64'd66588 << 6);
  wire [63:0] bg_ffnup_b_load_base_addr = bg_preload_layer_base_addr + (64'd132636 << 6);
  wire [63:0] bg_ffndown_w_load_base_addr = bg_preload_layer_base_addr + (64'd132892 << 6);
  wire [63:0] bg_ffndown_b_load_base_addr = bg_preload_layer_base_addr + (64'd200476 << 6);
  wire [31:0] core_data_in_addr;
  wire run_last_token = (run_token_idx == cfg_seqlen_word);
  wire run_last_layer = (layer_idx == (NUM_LAYERS[3:0] - 4'd1));
  wire [3:0] next_layer_idx_w = layer_idx + 4'd1;
  wire next_weight_bank = ~active_weight_bank;
  wire next_layer_bank_ready =
      weight_bank_valid[next_weight_bank] &&
      (weight_bank_layer_idx[next_weight_bank] == next_layer_idx_w);
  wire is_load_state =
      state == ST_LOAD_LN1 || state == ST_LOAD_QKV_W || state == ST_LOAD_QKV_B ||
      state == ST_LOAD_SM || state == ST_LOAD_OUT_W || state == ST_LOAD_OUT_B ||
      state == ST_LOAD_LN2 || state == ST_LOAD_FFNUP_W || state == ST_LOAD_FFNUP_B ||
      state == ST_LOAD_FFNDOWN_W || state == ST_LOAD_FFNDOWN_B || state == ST_LOAD_TOKEN;

  (* max_fanout = 32 *) wire core_layer_st = (state == ST_PRELOAD_PULSE);
  (* max_fanout = 32 *) wire core_cfg_valid = (state == ST_RUN_CFG);
  wire [3:0] core_cfg_seqlen = cfg_seqlen_word[3:0];
  wire core_cfg_prefill = 1'b1;
  // Attention runtime requests are token-scoped in both long/short sequence flows.
  // Keep the attention-side cfg aligned with that token boundary instead of
  // reusing the monolithic short-seq prefill cfg, otherwise DM1/VCache/DM2
  // counters will span multiple tokens inside one "request".
  wire [15:0] core_attn_cfg_seqlen = run_token_idx;
  wire core_attn_cfg_prefill = 1'b0;
  (* max_fanout = 32 *) wire core_attn_cfg_valid = (state == ST_RUN_CFG);
  wire core_attn_cfg_single_query = 1'b1;
  wire core_ln_w_valid = (state == ST_STREAM_LN1);
  wire core_ln2_w_valid = (state == ST_STREAM_LN2);
  wire core_qkv_b_valid = (state == ST_STREAM_QKV_B);
  wire core_out_b_valid = (state == ST_STREAM_OUT_B);
  wire core_ffnup_b_valid = (state == ST_STREAM_FFNUP_B);
  wire core_ffndown_b_valid = (state == ST_STREAM_FFNDOWN_B);
  (* max_fanout = 32 *) wire core_reset = reset || (state == ST_CORE_RESET);
  wire [47:0] core_attn_tap_data;
  wire [3:0] core_attn_tap_head;
  wire [31:0] core_attn_tap_addr;
  wire core_attn_tap_valid;
  wire core_attn_tap_last;
  wire core_attn_dm1_override_ready;
  wire core_attn_dm2_v_override_ready;
  wire core_attn_dm1_override_enable = 1'b0;
  wire [31:0] core_attn_dm1_override_data = 32'd0;
  wire core_attn_dm1_override_st = 1'b0;
  wire [9:0] core_attn_dm1_override_addr = 10'd0;
  wire core_attn_dm1_override_valid = 1'b0;
  wire core_attn_dm1_override_last = 1'b0;
  wire core_attn_dm2_v_override_enable = 1'b0;
  wire [511:0] core_attn_dm2_v_override_data = 512'd0;
  wire core_attn_dm2_v_override_valid = 1'b0;
  wire [10:0] core_sm_w_addr;
  wire [383:0] core_res;
  wire core_res_st;
  wire [10:0] core_res_addr;
  wire core_res_valid;
  wire core_res_last;
  wire core_res_ready;

  wire [383:0] core_data_in = act_buf[active_act_bank][core_data_in_addr[9:0]];
  wire [383:0] core_ln1_w_in = ln1_w_mem[active_weight_bank][stream_cnt[6:0]];
  wire [383:0] core_ln2_w_in = ln2_w_mem[active_weight_bank][stream_cnt[6:0]];
  wire [95:0]  core_qkv_b_in = qkv_b_mem[active_weight_bank][stream_cnt[7:0]];
  wire [SOFTMAX_SEQ_LEN-1:0] core_sm_w_in =
      (core_sm_w_addr < SM_BEATS[10:0])
          ? sm_w_mem[core_sm_w_addr[4:0]]
          : {SOFTMAX_SEQ_LEN{1'b0}};
  wire [287:0] core_qkv_w_in = 288'd0;
  wire [287:0] core_out_w_in = 288'd0;
  wire [383:0] core_out_b_in = out_b_mem[active_weight_bank][stream_cnt[5:0]];
  wire [287:0] core_ffnup_w_in = 288'd0;
  wire [95:0]  core_ffnup_b_in = ffnup_b_mem[active_weight_bank][stream_cnt[7:0]];
  wire [287:0] core_ffndown_w_in = 288'd0;
  wire [383:0] core_ffndown_b_in = ffndown_b_mem[active_weight_bank][stream_cnt[5:0]];

  wire main_load_rd_fire = rd_fire && !rd_bg_preload_active;
  wire bg_load_rd_fire = rd_fire && rd_bg_preload_active;
  wire core_qkv_w_preload_valid =
      ((state == ST_LOAD_QKV_W) && main_load_rd_fire) ||
      (bg_load_rd_fire && (bg_preload_state == ST_LOAD_QKV_W));
  wire [15:0] core_qkv_w_preload_addr =
      (bg_load_rd_fire && (bg_preload_state == ST_LOAD_QKV_W)) ? bg_recv_count[15:0] : recv_count[15:0];
  wire [287:0] core_qkv_w_preload_data = c0_ddr4_s_axi_rdata[287:0];
  wire core_out_w_preload_valid =
      ((state == ST_LOAD_OUT_W) && main_load_rd_fire) ||
      (bg_load_rd_fire && (bg_preload_state == ST_LOAD_OUT_W));
  wire [14:0] core_out_w_preload_addr =
      (bg_load_rd_fire && (bg_preload_state == ST_LOAD_OUT_W)) ? bg_recv_count[14:0] : recv_count[14:0];
  wire [287:0] core_out_w_preload_data = c0_ddr4_s_axi_rdata[287:0];
  wire core_ffnup_w_preload_valid =
      ((state == ST_LOAD_FFNUP_W) && main_load_rd_fire) ||
      (bg_load_rd_fire && (bg_preload_state == ST_LOAD_FFNUP_W));
  wire [16:0] core_ffnup_w_preload_addr =
      (bg_load_rd_fire && (bg_preload_state == ST_LOAD_FFNUP_W)) ? bg_recv_count[16:0] : recv_count[16:0];
  wire [287:0] core_ffnup_w_preload_data = c0_ddr4_s_axi_rdata[287:0];
  wire core_ffndown_w_preload_valid =
      ((state == ST_LOAD_FFNDOWN_W) && main_load_rd_fire) ||
      (bg_load_rd_fire && (bg_preload_state == ST_LOAD_FFNDOWN_W));
  wire [16:0] core_ffndown_w_preload_addr =
      (bg_load_rd_fire && (bg_preload_state == ST_LOAD_FFNDOWN_W)) ? bg_recv_count[16:0] : recv_count[16:0];
  wire [287:0] core_ffndown_w_preload_data = c0_ddr4_s_axi_rdata[287:0];

  wire [63:0] issue_addr64 = cur_base_addr + ({32'd0, issue_count} << 6);
  reg [63:0] bg_cur_base_addr;
  reg [31:0] bg_cur_len;
  wire [63:0] bg_issue_addr64 = bg_cur_base_addr + ({32'd0, bg_issue_count} << 6);
  wire bg_preload_issue = bg_preload_active && (bg_issue_count < bg_cur_len);
  wire [7:0] preload_wait_target = preload_full_wait ? 8'd63 : 8'd3;
  wire short_writeback_state = (state == ST_WRITEBACK);
  wire write_state = short_writeback_state;
  wire [63:0] write_issue_addr64 =
      output_base_addr + ({53'd0, writeback_idx} * output_stride_bytes);

  assign core_res_ready = (state == ST_RUN);
  assign cnn0_batch_count = {2'd0, (state != ST_IDLE)};
  assign cnn0_result_count = {2'd0, result_done};

  Top u_core (
    .clock(clock),
    .reset(core_reset),
    .io_layer_st(core_layer_st),
    .io_cfg_seqlen(core_cfg_seqlen),
    .io_cfg_prefill(core_cfg_prefill),
    .io_cfg_valid(core_cfg_valid),
    .io_attn_cfg_seqlen(core_attn_cfg_seqlen),
    .io_attn_cfg_prefill(core_attn_cfg_prefill),
    .io_attn_cfg_valid(core_attn_cfg_valid),
    .io_attn_cfg_single_query(core_attn_cfg_single_query),
    .io_weight_init_mode(1'b0),
    .io_weight_active_bank(active_weight_bank),
    .io_weight_preload_bank(is_load_state ? weight_init_bank_sel
                                         : (bg_preload_active ? bg_preload_bank_sel : weight_init_bank_sel)),
    .io_data_in(core_data_in),
    .io_data_in_ready((state == ST_RUN)),
    .io_data_in_addr(core_data_in_addr),
    .io_attn_tap_data(core_attn_tap_data),
    .io_attn_tap_head(core_attn_tap_head),
    .io_attn_tap_addr(core_attn_tap_addr),
    .io_attn_tap_valid(core_attn_tap_valid),
    .io_attn_tap_last(core_attn_tap_last),
    .io_attn_dm1_override_enable(core_attn_dm1_override_enable),
    .io_attn_dm1_override_data(core_attn_dm1_override_data),
    .io_attn_dm1_override_st(core_attn_dm1_override_st),
    .io_attn_dm1_override_addr(core_attn_dm1_override_addr),
    .io_attn_dm1_override_valid(core_attn_dm1_override_valid),
    .io_attn_dm1_override_last(core_attn_dm1_override_last),
    .io_attn_dm1_override_ready(core_attn_dm1_override_ready),
    .io_attn_dm2_v_override_enable(core_attn_dm2_v_override_enable),
    .io_attn_dm2_v_override_data(core_attn_dm2_v_override_data),
    .io_attn_dm2_v_override_valid(core_attn_dm2_v_override_valid),
    .io_attn_dm2_v_override_ready(core_attn_dm2_v_override_ready),
    .io_ln_w_in(core_ln1_w_in),
    .io_ln_w_valid(core_ln_w_valid),
    .io_ln1_out_inv_scale(ln1_out_inv_scale),
    .io_ln1_out_zero_point(ln1_zero_point),
    .io_q_out_inv_scale(q_out_inv_scale),
    .io_k_out_inv_scale(k_out_inv_scale),
    .io_v_out_inv_scale(v_out_inv_scale),
    .io_q_bias_scale(q_bias_scale),
    .io_k_bias_scale(k_bias_scale),
    .io_v_bias_scale(v_bias_scale),
    .io_dm1_out_scale(dm1_out_scale),
    .io_dm2_ctx_inv_scale(dm2_ctx_inv_scale),
    .io_dm2_ctx_zero_point(dm2_ctx_zero_point),
    .io_dm2_out_inv_scale(dm2_out_inv_scale),
    .io_out_out_scale(out_out_scale),
    .io_ln2_out_inv_scale(ln2_out_inv_scale),
    .io_ln2_out_zero_point(ln2_zero_point),
    .io_ffnup_out_inv_scale(ffnup_out_inv_scale),
    .io_ffnup_bias_scale(ffnup_bias_scale),
    .io_ffndown_out_scale(ffndown_out_scale),
    .io_qkv_w_in(core_qkv_w_in),
    .io_qkv_w_addr(),
    .io_qkv_w_preload_valid(core_qkv_w_preload_valid),
    .io_qkv_w_preload_addr(core_qkv_w_preload_addr),
    .io_qkv_w_preload_data(core_qkv_w_preload_data),
    .io_qkv_b_in(core_qkv_b_in),
    .io_qkv_b_valid(core_qkv_b_valid),
    .io_sm_w_in(core_sm_w_in),
    .io_sm_w_addr(core_sm_w_addr),
    .io_out_w_in(core_out_w_in),
    .io_out_w_addr(),
    .io_out_w_preload_valid(core_out_w_preload_valid),
    .io_out_w_preload_addr(core_out_w_preload_addr),
    .io_out_w_preload_data(core_out_w_preload_data),
    .io_out_b_in(core_out_b_in),
    .io_out_b_valid(core_out_b_valid),
    .io_ln2_w_in(core_ln2_w_in),
    .io_ln2_w_valid(core_ln2_w_valid),
    .io_ffnup_w_in(core_ffnup_w_in),
    .io_ffnup_w_addr(),
    .io_ffnup_w_preload_valid(core_ffnup_w_preload_valid),
    .io_ffnup_w_preload_addr(core_ffnup_w_preload_addr),
    .io_ffnup_w_preload_data(core_ffnup_w_preload_data),
    .io_ffnup_b_in(core_ffnup_b_in),
    .io_ffnup_b_valid(core_ffnup_b_valid),
    .io_ffndown_w_in(core_ffndown_w_in),
    .io_ffndown_w_addr(),
    .io_ffndown_w_preload_valid(core_ffndown_w_preload_valid),
    .io_ffndown_w_preload_addr(core_ffndown_w_preload_addr),
    .io_ffndown_w_preload_data(core_ffndown_w_preload_data),
    .io_ffndown_b_in(core_ffndown_b_in),
    .io_ffndown_b_valid(core_ffndown_b_valid),
    .io_res(core_res),
    .io_res_st(core_res_st),
    .io_res_addr(core_res_addr),
    .io_res_valid(core_res_valid),
    .io_res_last(core_res_last),
    .io_res_ready(core_res_ready)
  );

  always @(*) begin
    case (state)
      ST_LOAD_TOKEN:      begin
                            cur_base_addr = input_base_addr;
                            cur_len = {21'd0, short_total_beats};
                          end
      ST_LOAD_LN1:        begin cur_base_addr = ln1_w_load_base_addr;     cur_len = LN_WEIGHT_BEATS; end
      ST_LOAD_QKV_W:      begin cur_base_addr = qkv_w_load_base_addr;     cur_len = QKV_WEIGHT_BEATS; end
      ST_LOAD_QKV_B:      begin cur_base_addr = qkv_b_load_base_addr;     cur_len = QKV_BIAS_BEATS; end
      ST_LOAD_SM:         begin cur_base_addr = sm_load_base_addr;        cur_len = SM_BEATS; end
      ST_LOAD_OUT_W:      begin cur_base_addr = out_w_load_base_addr;     cur_len = OUT_WEIGHT_BEATS; end
      ST_LOAD_OUT_B:      begin cur_base_addr = out_b_load_base_addr;     cur_len = OUT_BIAS_BEATS; end
      ST_LOAD_LN2:        begin cur_base_addr = ln2_w_load_base_addr;     cur_len = LN_WEIGHT_BEATS; end
      ST_LOAD_FFNUP_W:    begin cur_base_addr = ffnup_w_load_base_addr;   cur_len = FFNUP_WEIGHT_BEATS; end
      ST_LOAD_FFNUP_B:    begin cur_base_addr = ffnup_b_load_base_addr;   cur_len = FFNUP_BIAS_BEATS; end
      ST_LOAD_FFNDOWN_W:  begin cur_base_addr = ffndown_w_load_base_addr; cur_len = FFNDOWN_WEIGHT_BEATS; end
      ST_LOAD_FFNDOWN_B:  begin cur_base_addr = ffndown_b_load_base_addr; cur_len = FFNDOWN_BIAS_BEATS; end
      default:            begin cur_base_addr = 64'd0;               cur_len = 32'd0; end
    endcase
  end

  always @(*) begin
    case (bg_preload_state)
      ST_LOAD_LN1:        begin bg_cur_base_addr = bg_ln1_w_load_base_addr;     bg_cur_len = LN_WEIGHT_BEATS; end
      ST_LOAD_QKV_W:      begin bg_cur_base_addr = bg_qkv_w_load_base_addr;     bg_cur_len = QKV_WEIGHT_BEATS; end
      ST_LOAD_QKV_B:      begin bg_cur_base_addr = bg_qkv_b_load_base_addr;     bg_cur_len = QKV_BIAS_BEATS; end
      ST_LOAD_SM:         begin bg_cur_base_addr = bg_sm_load_base_addr;        bg_cur_len = SM_BEATS; end
      ST_LOAD_OUT_W:      begin bg_cur_base_addr = bg_out_w_load_base_addr;     bg_cur_len = OUT_WEIGHT_BEATS; end
      ST_LOAD_OUT_B:      begin bg_cur_base_addr = bg_out_b_load_base_addr;     bg_cur_len = OUT_BIAS_BEATS; end
      ST_LOAD_LN2:        begin bg_cur_base_addr = bg_ln2_w_load_base_addr;     bg_cur_len = LN_WEIGHT_BEATS; end
      ST_LOAD_FFNUP_W:    begin bg_cur_base_addr = bg_ffnup_w_load_base_addr;   bg_cur_len = FFNUP_WEIGHT_BEATS; end
      ST_LOAD_FFNUP_B:    begin bg_cur_base_addr = bg_ffnup_b_load_base_addr;   bg_cur_len = FFNUP_BIAS_BEATS; end
      ST_LOAD_FFNDOWN_W:  begin bg_cur_base_addr = bg_ffndown_w_load_base_addr; bg_cur_len = FFNDOWN_WEIGHT_BEATS; end
      ST_LOAD_FFNDOWN_B:  begin bg_cur_base_addr = bg_ffndown_b_load_base_addr; bg_cur_len = FFNDOWN_BIAS_BEATS; end
      default:            begin bg_cur_base_addr = 64'd0;                        bg_cur_len = 32'd0; end
    endcase
  end

  always @(posedge clock) begin
    if (reset) begin
      state <= ST_IDLE;
      issue_count <= 32'd0;
      recv_count <= 32'd0;
      core_reset_cnt <= 8'd0;
      preload_wait_cnt <= 8'd0;
      stream_cnt <= 32'd0;
      run_token_idx <= 16'd0;
      layer_idx <= 4'd0;
      preload_full_wait <= 1'b1;
      active_weight_bank <= 1'b0;
      active_act_bank <= 1'b0;
      weight_init_bank_sel <= 1'b0;
      token_last_pending <= 1'b0;
      token_res_started <= 1'b0;
      token_last_core_addr <= 11'd0;
      writeback_idx <= 11'd0;
      cfg_shadow <= 704'd0;
      cfg_active <= 704'd0;
      cfg_loaded <= 1'b0;
      result_done <= 1'b0;
      rd_active <= 1'b0;
      preload_layer_idx <= 4'd0;
      pending_layer_idx <= 4'd0;
      pending_layer_switch <= 1'b0;
      weight_bank_layer_idx[0] <= 4'd0;
      weight_bank_layer_idx[1] <= 4'd0;
      weight_bank_valid <= 2'b00;
      bg_preload_active <= 1'b0;
      bg_preload_bank_sel <= 1'b0;
      bg_preload_layer_idx <= 4'd0;
      bg_preload_state <= ST_IDLE;
      bg_issue_count <= 32'd0;
      bg_recv_count <= 32'd0;
      rd_bg_preload_active <= 1'b0;
      short_inputs_loaded <= 1'b0;

      c0_ddr4_s_axi_awid <= 4'd0;
      c0_ddr4_s_axi_awaddr <= 37'd0;
      c0_ddr4_s_axi_awlen <= 8'd0;
      c0_ddr4_s_axi_awsize <= 3'd6;
      c0_ddr4_s_axi_awburst <= 2'b01;
      c0_ddr4_s_axi_awlock <= 1'b0;
      c0_ddr4_s_axi_awcache <= 4'd0;
      c0_ddr4_s_axi_awprot <= 3'd0;
      c0_ddr4_s_axi_awvalid <= 1'b0;

      c0_ddr4_s_axi_wdata <= 512'd0;
      c0_ddr4_s_axi_wstrb <= 64'd0;
      c0_ddr4_s_axi_wlast <= 1'b0;
      c0_ddr4_s_axi_wvalid <= 1'b0;
      c0_ddr4_s_axi_bready <= 1'b0;

      c0_ddr4_s_axi_arid <= 4'd0;
      c0_ddr4_s_axi_araddr <= 37'd0;
      c0_ddr4_s_axi_arlen <= 8'd0;
      c0_ddr4_s_axi_arsize <= 3'd6;
      c0_ddr4_s_axi_arburst <= 2'b01;
      c0_ddr4_s_axi_arlock <= 1'b0;
      c0_ddr4_s_axi_arcache <= 4'd0;
      c0_ddr4_s_axi_arprot <= 3'd0;
      c0_ddr4_s_axi_arvalid <= 1'b0;
      c0_ddr4_s_axi_rready <= 1'b0;
    end else begin
      if (cfg_data_valid)
        cfg_shadow <= cfg_data;
      if (cfg_done) begin
        cfg_active <= cfg_data_valid ? cfg_data : cfg_shadow;
        cfg_loaded <= 1'b1;
      end

      if (state == ST_DONE && cnn0_result_batch_clear) begin
        result_done <= 1'b0;
        state <= ST_IDLE;
      end

      if (!c0_ddr4_s_axi_arvalid && !rd_active && c0_init_calib_complete &&
          ((is_load_state && (issue_count < cur_len)) || bg_preload_issue)) begin
        c0_ddr4_s_axi_arid <= 4'd0;
        if (is_load_state)
          c0_ddr4_s_axi_araddr <= issue_addr64[36:0];
        else
          c0_ddr4_s_axi_araddr <= bg_issue_addr64[36:0];
        c0_ddr4_s_axi_arlen <= 8'd0;
        c0_ddr4_s_axi_arsize <= 3'd6;
        c0_ddr4_s_axi_arburst <= 2'b01;
        c0_ddr4_s_axi_arlock <= 1'b0;
        c0_ddr4_s_axi_arcache <= 4'd0;
        c0_ddr4_s_axi_arprot <= 3'd0;
        c0_ddr4_s_axi_arvalid <= 1'b1;
      end

      if (c0_ddr4_s_axi_arvalid && c0_ddr4_s_axi_arready) begin
        c0_ddr4_s_axi_arvalid <= 1'b0;
        c0_ddr4_s_axi_rready <= 1'b1;
        rd_active <= 1'b1;
        rd_bg_preload_active <= !is_load_state && bg_preload_issue;
        if (is_load_state)
          issue_count <= issue_count + 32'd1;
        else
          bg_issue_count <= bg_issue_count + 32'd1;
      end

      if (rd_fire) begin
        rd_active <= 1'b0;
        c0_ddr4_s_axi_rready <= 1'b0;
        rd_bg_preload_active <= 1'b0;
        if (rd_bg_preload_active) begin
          case (bg_preload_state)
            ST_LOAD_LN1:        ln1_w_mem[bg_preload_bank_sel][bg_recv_count[6:0]] <= c0_ddr4_s_axi_rdata[383:0];
            ST_LOAD_QKV_B:      qkv_b_mem[bg_preload_bank_sel][bg_recv_count[7:0]] <= c0_ddr4_s_axi_rdata[95:0];
            ST_LOAD_SM:         sm_w_mem[bg_recv_count[4:0]] <= c0_ddr4_s_axi_rdata[SOFTMAX_SEQ_LEN-1:0];
            ST_LOAD_OUT_B:      out_b_mem[bg_preload_bank_sel][bg_recv_count[5:0]] <= c0_ddr4_s_axi_rdata[383:0];
            ST_LOAD_LN2:        ln2_w_mem[bg_preload_bank_sel][bg_recv_count[6:0]] <= c0_ddr4_s_axi_rdata[383:0];
            ST_LOAD_FFNUP_B:    ffnup_b_mem[bg_preload_bank_sel][bg_recv_count[7:0]] <= c0_ddr4_s_axi_rdata[95:0];
            ST_LOAD_FFNDOWN_B:  ffndown_b_mem[bg_preload_bank_sel][bg_recv_count[5:0]] <= c0_ddr4_s_axi_rdata[383:0];
            default: begin end
          endcase
        end else begin
          case (state)
            ST_LOAD_TOKEN:      act_buf[active_act_bank][recv_count[9:0]] <= c0_ddr4_s_axi_rdata[383:0];
            ST_LOAD_LN1:        ln1_w_mem[weight_init_bank_sel][recv_count[6:0]] <= c0_ddr4_s_axi_rdata[383:0];
            ST_LOAD_QKV_B:      qkv_b_mem[weight_init_bank_sel][recv_count[7:0]] <= c0_ddr4_s_axi_rdata[95:0];
            ST_LOAD_SM:         sm_w_mem[recv_count[4:0]] <= c0_ddr4_s_axi_rdata[SOFTMAX_SEQ_LEN-1:0];
            ST_LOAD_OUT_B:      out_b_mem[weight_init_bank_sel][recv_count[5:0]] <= c0_ddr4_s_axi_rdata[383:0];
            ST_LOAD_LN2:        ln2_w_mem[weight_init_bank_sel][recv_count[6:0]] <= c0_ddr4_s_axi_rdata[383:0];
            ST_LOAD_FFNUP_B:    ffnup_b_mem[weight_init_bank_sel][recv_count[7:0]] <= c0_ddr4_s_axi_rdata[95:0];
            ST_LOAD_FFNDOWN_B:  ffndown_b_mem[weight_init_bank_sel][recv_count[5:0]] <= c0_ddr4_s_axi_rdata[383:0];
            default: begin end
          endcase
        end

        if (rd_bg_preload_active) begin
          if (bg_recv_count + 32'd1 == bg_cur_len) begin
            bg_issue_count <= 32'd0;
            bg_recv_count <= 32'd0;
            case (bg_preload_state)
              ST_LOAD_LN1:        bg_preload_state <= ST_LOAD_QKV_W;
              ST_LOAD_QKV_W:      bg_preload_state <= ST_LOAD_QKV_B;
              ST_LOAD_QKV_B:      bg_preload_state <= ST_LOAD_SM;
              ST_LOAD_SM:         bg_preload_state <= ST_LOAD_OUT_W;
              ST_LOAD_OUT_W:      bg_preload_state <= ST_LOAD_OUT_B;
              ST_LOAD_OUT_B:      bg_preload_state <= ST_LOAD_LN2;
              ST_LOAD_LN2:        bg_preload_state <= ST_LOAD_FFNUP_W;
              ST_LOAD_FFNUP_W:    bg_preload_state <= ST_LOAD_FFNUP_B;
              ST_LOAD_FFNUP_B:    bg_preload_state <= ST_LOAD_FFNDOWN_W;
              ST_LOAD_FFNDOWN_W:  bg_preload_state <= ST_LOAD_FFNDOWN_B;
              ST_LOAD_FFNDOWN_B:  begin
                                    weight_bank_valid[bg_preload_bank_sel] <= 1'b1;
                                    weight_bank_layer_idx[bg_preload_bank_sel] <= bg_preload_layer_idx;
                                    bg_preload_active <= 1'b0;
                                    bg_preload_state <= ST_IDLE;
                                  end
              default: begin end
            endcase
          end else begin
            bg_recv_count <= bg_recv_count + 32'd1;
          end
        end else if (recv_count + 32'd1 == cur_len) begin
          issue_count <= 32'd0;
          recv_count <= 32'd0;
          case (state)
            ST_LOAD_TOKEN:      begin
                                  short_inputs_loaded <= 1'b1;
                                  stream_cnt <= 32'd0;
                                  state <= ST_STREAM_LN1;
                                end
            ST_LOAD_LN1:        begin state <= ST_LOAD_QKV_W; end
            ST_LOAD_QKV_W:      begin state <= ST_LOAD_QKV_B; end
            ST_LOAD_QKV_B:      state <= ST_LOAD_SM;
            ST_LOAD_SM:         state <= ST_LOAD_OUT_W;
            ST_LOAD_OUT_W:      begin state <= ST_LOAD_OUT_B; end
            ST_LOAD_OUT_B:      state <= ST_LOAD_LN2;
            ST_LOAD_LN2:        state <= ST_LOAD_FFNUP_W;
            ST_LOAD_FFNUP_W:    begin state <= ST_LOAD_FFNUP_B; end
            ST_LOAD_FFNUP_B:    state <= ST_LOAD_FFNDOWN_W;
            ST_LOAD_FFNDOWN_W:  begin state <= ST_LOAD_FFNDOWN_B; end
            ST_LOAD_FFNDOWN_B:  begin
                                  weight_bank_valid[weight_init_bank_sel] <= 1'b1;
                                  weight_bank_layer_idx[weight_init_bank_sel] <= preload_layer_idx;
                                  stream_cnt <= 32'd0;
                                  if (pending_layer_switch) begin
                                    pending_layer_switch <= 1'b0;
                                    layer_idx <= pending_layer_idx;
                                    active_weight_bank <= weight_init_bank_sel;
                                    active_act_bank <= ~active_act_bank;
                                    preload_full_wait <= 1'b1;
                                    state <= ST_STREAM_LN1;
                                  end else if (short_seq_all_layers_mode &&
                                               !short_inputs_loaded &&
                                               (weight_init_bank_sel == 1'b0) &&
                                               (preload_layer_idx + 4'd1 < NUM_LAYERS[3:0])) begin
                                    preload_layer_idx <= preload_layer_idx + 4'd1;
                                    weight_init_bank_sel <= 1'b1;
                                    state <= ST_LOAD_LN1;
                                  end else if (!short_inputs_loaded)
                                    state <= ST_LOAD_TOKEN;
                                  else
                                    state <= ST_STREAM_LN1;
                                end
            default: begin end
          endcase
        end else begin
          recv_count <= recv_count + 32'd1;
        end
      end

      if (!c0_ddr4_s_axi_awvalid && !c0_ddr4_s_axi_wvalid && !c0_ddr4_s_axi_bready &&
          write_state &&
          (writeback_idx < short_total_beats) &&
          c0_init_calib_complete) begin
        c0_ddr4_s_axi_awid <= 4'd0;
        c0_ddr4_s_axi_awaddr <= write_issue_addr64[36:0];
        c0_ddr4_s_axi_awlen <= 8'd0;
        c0_ddr4_s_axi_awsize <= 3'd6;
        c0_ddr4_s_axi_awburst <= 2'b01;
        c0_ddr4_s_axi_awlock <= 1'b0;
        c0_ddr4_s_axi_awcache <= 4'd0;
        c0_ddr4_s_axi_awprot <= 3'd0;
        c0_ddr4_s_axi_awvalid <= 1'b1;
        writeback_buf <= {128'd0, act_buf[~active_act_bank][writeback_idx[9:0]]};
      end

      if (c0_ddr4_s_axi_awvalid && c0_ddr4_s_axi_awready) begin
        c0_ddr4_s_axi_awvalid <= 1'b0;
        c0_ddr4_s_axi_wdata <= writeback_buf;
        c0_ddr4_s_axi_wstrb <= 64'h0000FFFFFFFFFFFF;
        c0_ddr4_s_axi_wlast <= 1'b1;
        c0_ddr4_s_axi_wvalid <= 1'b1;
      end

      if (c0_ddr4_s_axi_wvalid && c0_ddr4_s_axi_wready) begin
        c0_ddr4_s_axi_wvalid <= 1'b0;
        c0_ddr4_s_axi_wlast <= 1'b0;
        c0_ddr4_s_axi_bready <= 1'b1;
      end

      if (c0_ddr4_s_axi_bvalid && c0_ddr4_s_axi_bready) begin
        c0_ddr4_s_axi_bready <= 1'b0;
        if (short_writeback_state) begin
          if (writeback_idx + 11'd1 == short_total_beats) begin
            writeback_idx <= 11'd0;
            state <= ST_DONE;
            result_done <= 1'b1;
`ifndef SYNTHESIS
            $display("opt_acc_core shortseq-done layers=%0d beats=%0d",
                     NUM_LAYERS, short_total_beats);
`endif
          end else begin
            writeback_idx <= writeback_idx + 11'd1;
          end
        end
      end

      if (state == ST_RUN && core_res_valid && core_res_ready) begin
        if (core_res_st)
          token_res_started <= 1'b1;
        act_buf[~active_act_bank][core_res_addr[9:0]] <= core_res;
        if (core_res_last && (token_res_started || core_res_st)) begin
          token_last_pending <= 1'b1;
          token_last_core_addr <= core_res_addr;
        end
      end

      case (state)
        ST_IDLE: begin
          issue_count <= 32'd0;
          recv_count <= 32'd0;
          run_token_idx <= 16'd0;
          layer_idx <= 4'd0;
          preload_full_wait <= 1'b1;
          active_weight_bank <= 1'b0;
          active_act_bank <= 1'b0;
          weight_init_bank_sel <= 1'b0;
          preload_layer_idx <= 4'd0;
          pending_layer_idx <= 4'd0;
          pending_layer_switch <= 1'b0;
          weight_bank_layer_idx[0] <= 4'd0;
          weight_bank_layer_idx[1] <= 4'd0;
          weight_bank_valid <= 2'b00;
          bg_preload_active <= 1'b0;
          bg_preload_bank_sel <= 1'b0;
          bg_preload_layer_idx <= 4'd0;
          bg_preload_state <= ST_IDLE;
          bg_issue_count <= 32'd0;
          bg_recv_count <= 32'd0;
          rd_bg_preload_active <= 1'b0;
          short_inputs_loaded <= 1'b0;
          token_last_pending <= 1'b0;
          token_res_started <= 1'b0;
          writeback_idx <= 11'd0;
          if (cfg_loaded && c0_init_calib_complete && cnn0_input_batch_set) begin
            result_done <= 1'b0;
            state <= ST_LOAD_LN1;
          end
        end
        ST_CORE_RESET: begin
          core_reset_cnt <= core_reset_cnt + 8'd1;
          if (core_reset_cnt == 8'd5) begin
            state <= ST_STREAM_LN1;
            stream_cnt <= 32'd0;
          end
        end
        ST_STREAM_LN1: begin stream_cnt <= stream_cnt + 32'd1; if (stream_cnt + 32'd1 == LN_WEIGHT_BEATS) begin state <= ST_STREAM_LN2; stream_cnt <= 32'd0; end end
        ST_STREAM_LN2: begin stream_cnt <= stream_cnt + 32'd1; if (stream_cnt + 32'd1 == LN_WEIGHT_BEATS) begin state <= ST_STREAM_QKV_B; stream_cnt <= 32'd0; end end
        ST_STREAM_QKV_B: begin stream_cnt <= stream_cnt + 32'd1; if (stream_cnt + 32'd1 == QKV_BIAS_BEATS) begin state <= ST_STREAM_OUT_B; stream_cnt <= 32'd0; end end
        ST_STREAM_OUT_B: begin stream_cnt <= stream_cnt + 32'd1; if (stream_cnt + 32'd1 == OUT_BIAS_BEATS) begin state <= ST_STREAM_FFNUP_B; stream_cnt <= 32'd0; end end
        ST_STREAM_FFNUP_B: begin stream_cnt <= stream_cnt + 32'd1; if (stream_cnt + 32'd1 == FFNUP_BIAS_BEATS) begin state <= ST_STREAM_FFNDOWN_B; stream_cnt <= 32'd0; end end
        ST_STREAM_FFNDOWN_B: begin
          stream_cnt <= stream_cnt + 32'd1;
          if (stream_cnt + 32'd1 == FFNDOWN_BIAS_BEATS) begin
            token_last_pending <= 1'b0;
            token_res_started <= 1'b0;
            preload_full_wait <= 1'b1;
            state <= ST_RUN_CFG;
          end
        end
        ST_PIPE_SCHED: begin
          // Long-sequence token scheduling has been removed.
          state <= ST_IDLE;
        end
        ST_RUN_CFG: begin
          token_res_started <= 1'b0;
          state <= ST_PRELOAD_PULSE;
        end
        ST_PRELOAD_PULSE: begin state <= ST_PRELOAD_WAIT; preload_wait_cnt <= 8'd0; end
        ST_PRELOAD_WAIT: begin
          preload_wait_cnt <= preload_wait_cnt + 8'd1;
          if (preload_wait_cnt == preload_wait_target) begin
            preload_full_wait <= 1'b0;
            if (short_seq_all_layers_mode && !run_last_layer &&
                !next_layer_bank_ready && !bg_preload_active) begin
              bg_preload_active <= 1'b1;
              bg_preload_bank_sel <= next_weight_bank;
              bg_preload_layer_idx <= next_layer_idx_w;
              bg_preload_state <= ST_LOAD_LN1;
              bg_issue_count <= 32'd0;
              bg_recv_count <= 32'd0;
              weight_bank_valid[next_weight_bank] <= 1'b0;
            end
            state <= ST_RUN;
          end
        end
        ST_RUN: begin
          if (token_last_pending) begin
            token_last_pending <= 1'b0;
            token_res_started <= 1'b0;
`ifndef SYNTHESIS
            $display("opt_acc_core token-done layer=%0d token=%0d cfg_seqlen=%0d run_last_token=%0d run_last_layer=%0d core_res_addr=%0d",
                     layer_idx, run_token_idx, cfg_seqlen_word, run_last_token, run_last_layer, token_last_core_addr);
`endif
            if (run_last_layer) begin
              writeback_idx <= 11'd0;
              state <= ST_WRITEBACK;
`ifndef SYNTHESIS
              $display("opt_acc_core shortseq-layer-done layer=%0d final=1 beats=%0d",
                       layer_idx, short_total_beats);
`endif
            end else if (short_seq_all_layers_mode) begin
              if (next_layer_bank_ready) begin
                layer_idx <= next_layer_idx_w;
                active_weight_bank <= next_weight_bank;
                active_act_bank <= ~active_act_bank;
                preload_full_wait <= 1'b1;
                stream_cnt <= 32'd0;
                state <= ST_STREAM_LN1;
`ifndef SYNTHESIS
                $display("opt_acc_core shortseq-layer-switch next_layer=%0d active_weight_bank=%0d active_act_bank=%0d",
                         next_layer_idx_w, next_weight_bank, ~active_act_bank);
`endif
              end else begin
                pending_layer_idx <= next_layer_idx_w;
                pending_layer_switch <= 1'b1;
                if (!bg_preload_active) begin
                  bg_preload_active <= 1'b1;
                  bg_preload_bank_sel <= next_weight_bank;
                  bg_preload_layer_idx <= next_layer_idx_w;
                  bg_preload_state <= ST_LOAD_LN1;
                  bg_issue_count <= 32'd0;
                  bg_recv_count <= 32'd0;
                  weight_bank_valid[next_weight_bank] <= 1'b0;
                end
                state <= ST_KVHIST_PREP;
`ifndef SYNTHESIS
                $display("opt_acc_core shortseq-layer-preload target_layer=%0d target_bank=%0d",
                         next_layer_idx_w, next_weight_bank);
`endif
              end
            end else begin
              layer_idx <= layer_idx + 4'd1;
              active_weight_bank <= ~active_weight_bank;
              active_act_bank <= ~active_act_bank;
              preload_full_wait <= 1'b1;
              stream_cnt <= 32'd0;
              state <= ST_STREAM_LN1;
`ifndef SYNTHESIS
              $display("opt_acc_core shortseq-layer-switch next_layer=%0d active_weight_bank=%0d active_act_bank=%0d",
                       layer_idx + 4'd1, ~active_weight_bank, ~active_act_bank);
`endif
            end
          end
        end
        ST_KVHIST_PREP: begin
          if (pending_layer_switch && next_layer_bank_ready) begin
            pending_layer_switch <= 1'b0;
            layer_idx <= pending_layer_idx;
            active_weight_bank <= next_weight_bank;
            active_act_bank <= ~active_act_bank;
            preload_full_wait <= 1'b1;
            stream_cnt <= 32'd0;
            state <= ST_STREAM_LN1;
`ifndef SYNTHESIS
            $display("opt_acc_core shortseq-layer-switch next_layer=%0d active_weight_bank=%0d active_act_bank=%0d",
                     pending_layer_idx, next_weight_bank, ~active_act_bank);
`endif
          end
        end
        ST_KVHIST_WRITE: begin state <= ST_IDLE; end
        ST_REPLAY_PREP: begin state <= ST_IDLE; end
        ST_REPLAY_READ: begin state <= ST_IDLE; end
        ST_REPLAY_STREAM: begin state <= ST_IDLE; end
        ST_WRITEBACK: begin end
        ST_DONE: begin end
        default: begin end
      endcase
    end
  end
endmodule
