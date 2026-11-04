# A fix of the GGUF loader of city96 for the LTX-2.5 weights of gaile.ee. Fails the build when the upstream code
# changes, so the patch is never applied blindly.
# The Q8_0 build of LTX-2.5 uncensored v1.1 (ChrisColeTech) quantizes its plain tensors too: the scale/shift tables of
# every block, the learnable registers of the text connectors, the keyframe position embedding (293 tensors; the
# Q4_K_M keeps them F16). The loader dequantizes a quantized tensor only in the layers it patches (their .weight), the
# plain tensors went in as their packed Q8_0 bytes (4352 = 2 x 2176 bytes of a 2048-wide row instead of 4096), and
# every Q8_0 run failed with "Sizes of tensors must match" (2026-11-04). A quantized tensor that is not the weight of
# a layer is dequantized when the file is loaded.
import pathlib
import sys

loader = pathlib.Path(sys.argv[1]) / "loader.py"
text = loader.read_text(encoding="utf-8")


def replace_once(old, new):
    global text
    if text.count(old) != 1:
        sys.exit("ComfyUI-GGUF loader.py changed, not found once: " + old[:80])
    text = text.replace(old, new)


replace_once(
    "        state_dict[sd_key] = GGMLTensor(torch_tensor, tensor_type=tensor.tensor_type, tensor_shape=shape)\n",
    "        state_dict[sd_key] = GGMLTensor(torch_tensor, tensor_type=tensor.tensor_type, tensor_shape=shape)\n"
    "\n"
    "        # a plain tensor (LTX scale/shift tables, connector registers) is not used by a patched layer\n"
    "        # (gaile.ee patch)\n"
    "        if not sd_key.endswith(\".weight\") and is_quantized(state_dict[sd_key]):\n"
    "            state_dict[sd_key] = dequantize_tensor(state_dict[sd_key], dtype=torch.float16)\n")
loader.write_text(text, encoding="utf-8")
print("ComfyUI-GGUF patched")
