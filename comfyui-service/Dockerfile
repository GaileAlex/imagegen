# ComfyUI for the image and video generation of gaile.ee. Pinned to the version the admin runs in StabilityMatrix
# (v0.42.1, torch 2.14.1+cu130), so the workflows built there give the same results here.
# The CUDA libraries come with the torch wheels (cu130 has the Blackwell kernels of the RTX 5060 Ti); the driver is
# given by the NVIDIA container runtime.
FROM ubuntu:24.04

RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    python3 python3-venv git ca-certificates tzdata libgl1 libglib2.0-0 && \
    rm -rf /var/lib/apt/lists/*

ENV PYTHONUNBUFFERED=1
ENV VIRTUAL_ENV=/opt/venv
ENV PATH=$VIRTUAL_ENV/bin:$PATH
RUN python3 -m venv $VIRTUAL_ENV && pip install --no-cache-dir --upgrade pip

RUN pip install --no-cache-dir torch==2.14.1 torchvision==0.29.1 torchaudio==2.11.0 \
    --index-url https://download.pytorch.org/whl/cu130

ARG COMFYUI_COMMIT=63be2f9b82ad0dac88a7ceaa28a9cf3154125a18
ARG GGUF_COMMIT=6ea2651e7df66d7585f6ffee804b20e92fb38b8a
RUN git clone https://github.com/comfyanonymous/ComfyUI /app && cd /app && git checkout $COMFYUI_COMMIT && \
    rm -rf .git && \
    git clone https://github.com/city96/ComfyUI-GGUF /app/custom_nodes/ComfyUI-GGUF && \
    cd /app/custom_nodes/ComfyUI-GGUF && git checkout $GGUF_COMMIT && rm -rf .git
RUN pip install --no-cache-dir -r /app/requirements.txt -r /app/custom_nodes/ComfyUI-GGUF/requirements.txt

# SwarmUI's own ComfyUI nodes (MIT): its sampler has its own seed noise and Krea 2 needs its prompt encoder and
# token weights, so the workflows taken from SwarmUI give the same pictures as the admin's SwarmUI runs.
# Same commit as the admin's SwarmUI; only the common nodes, the extra ones need rembg/yolo.
ARG SWARMUI_COMMIT=d74f8ce40d1e49161da61ddb74f87e6ebf11f7ad
ADD https://github.com/mcmonkeyprojects/SwarmUI/archive/$SWARMUI_COMMIT.tar.gz /tmp/swarmui.tar.gz
RUN tar -xzf /tmp/swarmui.tar.gz -C /tmp \
        SwarmUI-$SWARMUI_COMMIT/src/BuiltinExtensions/ComfyUIBackend/ExtraNodes/SwarmComfyCommon && \
    mv /tmp/SwarmUI-$SWARMUI_COMMIT/src/BuiltinExtensions/ComfyUIBackend/ExtraNodes/SwarmComfyCommon \
        /app/custom_nodes/SwarmComfyCommon && \
    rm -rf /tmp/swarmui.tar.gz /tmp/SwarmUI-$SWARMUI_COMMIT && \
    pip install --no-cache-dir opencv-python-headless==4.11.0.86

# Photo edits by instruction on Krea 2 / Muse: the nodes the Krea 2 General Edit LoRA was trained with (v2.0.0)
ARG KREA2EDIT_COMMIT=13ef60f20828949becada815d3cbc371eb5ccaab
RUN git clone https://github.com/lbouaraba/comfyui-krea2edit /app/custom_nodes/comfyui-krea2edit && \
    cd /app/custom_nodes/comfyui-krea2edit && git checkout $KREA2EDIT_COMMIT && rm -rf .git node.zip tests

# Triton compiles its launchers at run time (the int8 kernels of comfy-kitchen: Muse, the LTX encoder)
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends gcc python3-dev && \
    rm -rf /var/lib/apt/lists/*

# The models are a volume (copied once from StabilityMatrix, never a folder of Windows: 9p reads are slow);
# outputs and inputs are temporary, the Spring app fetches the results over the API
# (ubuntu:24.04 has its own user "ubuntu" with uid 1000)
RUN userdel --remove ubuntu && useradd --create-home --uid 1000 comfy && \
    install -d -o comfy -g comfy /models /io /io/input /io/output /app/temp /app/user /home/comfy/.nv && \
    chown -R comfy:comfy /app
COPY --chown=comfy:comfy extra_model_paths.yaml /app/extra_model_paths.yaml
USER comfy
WORKDIR /app

EXPOSE 8188

# --disable-auto-launch: no browser; models stay in VRAM until the Spring app asks /free (the idle unload is its job)
# /io = the volume imagegen-comfy-io, shared with cv-imagegen-app: it deletes the uploaded photos and the results
# once it has them (ComfyUI keeps them forever otherwise)
CMD ["python", "main.py", "--listen", "0.0.0.0", "--port", "8188", "--disable-auto-launch", \
     "--input-directory", "/io/input", "--output-directory", "/io/output"]
