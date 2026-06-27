#!/bin/bash
set -euo pipefail

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

if [ -f .env ]; then
    set -a
    source ./.env
    set +a
fi

if [ -z "${DB_PASSWORD:-}" ]; then
    echo -e "${RED}DB_PASSWORD is not set. Please provide it via the environment or .env file.${NC}"
    exit 1
fi

echo -e "${YELLOW}============================================================${NC}"
echo -e "${GREEN}[SYSTEM CHECK] Preparing the Spring Boot runtime...${NC}"
echo -e "${YELLOW}============================================================${NC}"

echo -e "${YELLOW}Checking whether port 8080 is already in use...${NC}"
PID=$(lsof -t -i:8080 || true)

if [ -n "$PID" ]; then
    echo -e "${RED}Port 8080 is already occupied by process PID: $PID.${NC}"
    echo -e "${RED}Killing process $PID...${NC}"
    kill -9 "$PID"
    sleep 1
    echo -e "${GREEN}Port 8080 has been released.${NC}"
else
    echo -e "${GREEN}Port 8080 is free.${NC}"
fi

echo -e "\n${GREEN}Starting Spring Boot with the single-node KRaft environment configuration...${NC}"
echo -e "${YELLOW}Tip: when you see 'Received: 0 records', the pipeline is ready and you can test it in Postman.${NC}\n"

DB_PASSWORD="$DB_PASSWORD" ./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Xms512m -Xmx512m -XX:+UseG1GC -XX:+UnlockDiagnosticVMOptions"