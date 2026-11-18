# 学校 GPU 容器 SSH 使用说明

> 给要在学校 GPU 上做模型微调的同学。推理接口的用法见 [SFT 接口说明](SFT接口说明.md)。
> 配置日期：2026-11-18。

## 一、容器信息

| 项 | 值 |
|---|---|
| SSH 地址 | `10.8.110.32`，端口 **`43998`**（对应容器内的 2222） |
| 用户名 | `zkpk`（大家共用） |
| 登录方式 | **只能用 SSH 密钥**，密码登录已关闭 |
| 推理接口 | `http://10.8.110.32:44855`（对应容器内的 8000） |
| 可访问范围 | 仅校园网 |
| 硬件 | NVIDIA L40 46 GB × 1；平台按 16 核 / 64 GB 内存申请（实际没做硬限制，但请别超用，整台机器是共享的）；磁盘约 8 TB |
| 软件 | Ubuntu、Python 3.10、torch 2.3.1+cu121、transformers 4.4x；**没有 sudo**，pip 包装在用户目录（`pip install` 即可） |
| 外网 | 能访问 GitHub、清华 pip 镜像、ModelScope、hf-mirror.com |

⚠️ 这两个端口号是平台分配的，**容器重建后会变**，以群里最新通知为准。

## 二、第一次登录

**1. 生成自己的密钥**（已经有的跳过）

```bash
ssh-keygen -t ed25519        # 一路回车即可
```

**2. 把公钥发给管理员**

```bash
cat ~/.ssh/id_ed25519.pub
```

把输出的那**一行**发过去（以 `ssh-ed25519` 开头），末尾最好改成自己的名字，方便管理。
**只发 `.pub` 公钥。没有 `.pub` 的那个是私钥，绝对不要发给任何人。**

**3. 登录**

管理员加好之后：

```bash
ssh -p 43998 zkpk@10.8.110.32
```

建议写进 `~/.ssh/config`，以后直接 `ssh yindun-gpu`：

```
Host yindun-gpu
    HostName 10.8.110.32
    Port 43998
    User zkpk
```

VS Code 装上 Remote-SSH 插件后，选 `yindun-gpu` 就能直接在容器里写代码。
传文件用 `scp -P 43998 本地文件 zkpk@10.8.110.32:~/work/你的名字/`（注意 scp 的端口参数是大写 `-P`）。

## 三、使用规则（大家共用一张卡，请务必遵守）

1. **各自在自己的目录工作**：`~/work/<名字>/`。不要改动别人的目录，也不要改 `~/yindun`、`~/models`、`~/sshd`
2. **训练前先停推理服务，训练完再开**。推理服务常驻约 18 GB 显存，7B 模型 LoRA 微调还要 25~40 GB，
   两者加起来超过 46 GB，同时跑会爆显存：
   ```bash
   pkill -f sft_server.py                    # 停推理服务
   # ……训练……
   cd ~/yindun && SFT_DEVICE=cuda ANTIFRAUD_DIR=$HOME/models/JimmyMa99/AntiFraud-SFT \
     nohup python3 tools/sft_server.py --host 0.0.0.0 --port 8000 > ~/sft.log 2>&1 &
   ```
   停服务前在群里说一声，演示期间不要停
3. **同一时间只跑一个训练任务**。开训前用 `nvidia-smi` 看一眼显存有没有被占用
4. **长任务一定要用 `nohup` 放到后台**，否则 SSH 一断训练就停了（容器里没有 tmux，也没有 sudo 权限装）：
   ```bash
   cd ~/work/名字
   nohup python3 train.py > train.log 2>&1 &
   tail -f train.log          # 看进度，Ctrl+C 只是退出查看，不会停训练
   ```
5. **只能做 LoRA / QLoRA**。7B 模型全参数微调这张卡装不下
6. 原始权重在 `~/models/JimmyMa99/AntiFraud-SFT`，大家共用，**只读**。
   训练输出放进自己的目录，不要覆盖原始权重

## 四、数据会丢，记得备份

**已实测：在平台上「删除实验」之后，容器磁盘会被整个清空。** 代码、权重、训练结果都没了。

- 训练出的 LoRA 权重和日志要及时拷回本地，或者推到 GitHub / ModelScope：
  ```bash
  scp -P 43998 -r zkpk@10.8.110.32:~/work/你的名字/output ./
  ```
- 容器重建后，SSH 服务和公钥也要重新配（见第五节），之前登记的公钥需要重新加

## 五、管理员操作

**添加学生公钥**（在容器里执行）：

```bash
echo 'ssh-ed25519 AAAA...学生公钥... 张三' >> ~/.ssh/authorized_keys
```

**移除某个人**：编辑 `~/.ssh/authorized_keys`，删掉对应那一行。

**容器重建后恢复 SSH**：平台自带的 22 端口转发不通，所以 SSH 用普通用户身份在 2222 端口另起一个。
建容器时端口要填 **8000 和 2222 两个**（点端口栏右边的 ⊕ 加第二个），然后在平台的网页终端里执行：

```bash
mkdir -p ~/.ssh ~/sshd && chmod 700 ~/.ssh
echo '管理员公钥' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys
ssh-keygen -q -t ed25519 -N '' -f ~/sshd/hostkey
cat > ~/sshd/config <<'EOF'
Port 2222
HostKey /home/zkpk/sshd/hostkey
PidFile /home/zkpk/sshd/sshd.pid
PasswordAuthentication no
KbdInteractiveAuthentication no
ChallengeResponseAuthentication no
PubkeyAuthentication yes
AuthorizedKeysFile .ssh/authorized_keys
UsePAM no
X11Forwarding no
Subsystem sftp /usr/lib/openssh/sftp-server
EOF
/usr/sbin/sshd -f ~/sshd/config -E ~/sshd/log
```

新的外放端口在平台容器页右侧「外放端口：port-2222:xxxxx」。
推理服务的重新部署步骤见 [SFT 接口说明](SFT接口说明.md) 第七节。

**容器重启但没有删除时**（比如服务器断电后重新拉起），sshd 不会自己启动，需要在网页终端里再执行最后一行。

**不用时删除容器**，把 GPU 让出来：平台容器页右侧齿轮 → 工具栏 →「删除实验」。
