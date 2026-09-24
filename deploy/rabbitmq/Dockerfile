# ============================================================
# RabbitMQ 自建镜像：把延迟插件 rabbitmq_delayed_message_exchange 装进镜像
#
# 为什么需要自建（实测事实，不是推测）：
#   官方镜像 rabbitmq:3.13-management **不含**该插件。在官方镜像里执行
#     rabbitmq-plugins enable rabbitmq_delayed_message_exchange
#   得到的是 {:plugins_not_found, [:rabbitmq_delayed_message_exchange]}，退出码 70
#   （实测 2026-09-24，broker 3.13.7）。插件是 RabbitMQ 官方维护、但**不随镜像分发**的
#   社区插件，必须以 .ez 文件形式放进 /opt/rabbitmq/plugins/ 再启用。
#
# 为什么 .ez 随仓库入库、而不是构建时下载：
#   目标部署机是腾讯云轻量服务器，GitHub release 的资产下载在境内常被限速/阻断，
#   把构建成功与否绑到网络运气上，会让"部署"变成不可复现的操作。
#   .ez 只有 45 KB，来源与校验值记录在同目录 README.md 里。
#
# 版本约束：插件必须与 broker 的 minor 版本匹配（此处 3.13.x ↔ 插件 3.13.0）。
#   升级 broker 时必须同步换插件包，否则启动期插件加载失败。
#   因此这里选**滚动标签 3-management**（当前解析为 3.13.7，仍在 3.x 内），
#   并在下面加一条构建期断言：tag 一旦漂到 3.14/4.x，构建**立即失败**，
#   而不是等到运行时插件加载不了（那会表现成"应用启动正常、publish 全失败"）。
#
# 为什么不是 3.13-management（更精确的标签）：2026-09-24 实测本机 Docker Hub 鉴权
#   不可达（auth.docker.io 超时），而 3-management 已在本地缓存、可离线构建；
#   精确性由下面的断言保证，不靠 tag 的写法。
# ============================================================
FROM rabbitmq:3-management

# 构建期断言：broker 必须是 3.13.x，否则插件包版本不匹配。
# 说明：这台镜像里 rabbitmqctl version **不需要 broker 在跑**（离线打印 3.13.7，实测），
#   所以可以在构建期用它做断言，把"tag 漂移"挡在构建阶段。
RUN rabbitmqctl version | grep -q '^3\.13\.' \
    || (echo "FATAL: 基础镜像不是 3.13.x，rabbitmq_delayed_message_exchange-3.13.0.ez 不匹配（见 deploy/rabbitmq/README.md）" && exit 1)

COPY rabbitmq_delayed_message_exchange-3.13.0.ez /opt/rabbitmq/plugins/

# --offline：构建期没有 broker 在运行，必须显式离线启用（否则 enable 会尝试连本地节点并失败）
RUN rabbitmq-plugins enable --offline rabbitmq_delayed_message_exchange

# 构建期二次断言：启用后插件必须出现在已启用列表里（防止 enable 静默无效）
RUN rabbitmq-plugins list -e | grep -q rabbitmq_delayed_message_exchange \
    || (echo "FATAL: 插件未出现在已启用列表" && exit 1)
