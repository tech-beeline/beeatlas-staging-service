ARG RUN_IMAGE=ubuntu:24.04
FROM ${RUN_IMAGE}

ENV DEBIAN_FRONTEND=noninteractive

# Системные зависимости и Python
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 \
    python3-pip \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r nonroot && useradd -r -g nonroot -s /usr/sbin/nologin nonroot

WORKDIR /app

COPY certs/ /usr/local/share/ca-certificates/
RUN update-ca-certificates
ENV REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt

ARG PIP_INDEX_URL='https://pypi.org/simple'
ENV PIP_INDEX_URL=$PIP_INDEX_URL
ARG PIP_TRUSTED_HOST=''
ENV PIP_TRUSTED_HOST=$PIP_TRUSTED_HOST
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY --chown=nonroot:nonroot app .

USER nonroot

EXPOSE 8080
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080"]
