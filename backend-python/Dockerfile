FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy

RUN pip install --no-cache-dir "uv==0.12.13"

WORKDIR /app
COPY backend-python/pyproject.toml backend-python/uv.lock ./
RUN uv sync --frozen --no-dev

COPY backend-python/app ./app
ENV PATH="/app/.venv/bin:${PATH}"

RUN useradd --system --home-dir /app --shell /usr/sbin/nologin knowbase \
    && chown -R knowbase:knowbase /app
USER knowbase

EXPOSE 8090
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090", "--no-access-log"]
