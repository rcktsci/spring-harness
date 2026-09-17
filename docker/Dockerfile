# Helper-образ per-session workspace-контейнера (D-M1-7, specs/workspace-tools).
# База — alpine (Open Question design.md): find/grep/coreutils(git) присутствуют в репозитории alpine;
# ставим явные пакеты, чтобы контракт инструментов (read/write/edit/glob/grep/bash) не зависел от busybox-апплетов.
FROM alpine:3.20

RUN apk add --no-cache \
        bash \
        findutils \
        grep \
        coreutils \
        git \
    && mkdir -p /workspace

WORKDIR /workspace

# Контейнер живёт до удаления рестарт-сканом (M1); команды приходят через docker exec.
CMD ["sh", "-c", "while true; do sleep 3600; done"]
