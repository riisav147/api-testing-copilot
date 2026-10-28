#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ ! -f .env.ai.properties ]; then
  echo 'Create .env.ai.properties with OPENAI_API_KEY and database settings. See README.md.' >&2
  exit 1
fi
exec ./mvnw spring-boot:run '-Dspring-boot.run.arguments=--spring.profiles.active=ai --spring.config.additional-location=file:./.env.ai.properties'
