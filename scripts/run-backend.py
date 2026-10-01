#!/usr/bin/env python3
"""Local-only launcher: resolve Compose env in memory; never print credentials."""
import argparse
import json
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
os.chdir(root)
parser = argparse.ArgumentParser()
parser.add_argument("--jar", action="store_true", help="Run the already-packaged backend JAR")
args = parser.parse_args()
result = subprocess.run(["docker", "compose", "config", "--format", "json"], capture_output=True, text=True)
if result.returncode:
    raise SystemExit("Unable to resolve Compose configuration; configure private .env first.")
services = json.loads(result.stdout)["services"]
postgres = services["postgres"]
config = postgres["environment"]
port = postgres["ports"][0]["published"]
env = dict(os.environ, DATABASE_URL=f"jdbc:postgresql://127.0.0.1:{port}/{config['POSTGRES_DB']}",
           DATABASE_USER=config["POSTGRES_USER"], DATABASE_PASSWORD=config["POSTGRES_PASSWORD"],
           SERVER_ADDRESS="127.0.0.1", AUTH_COOKIE_SECURE="false")
redis = services["redis"]
if redis["environment"]["REDIS_PASSWORD"] == "replace-with-a-unique-redis-password":
    raise SystemExit("Replace the private Redis password placeholder before starting the backend.")
env.update(REDIS_HOST="127.0.0.1", REDIS_PORT=str(redis["ports"][0]["published"]),
           REDIS_PASSWORD=redis["environment"]["REDIS_PASSWORD"],
           PUBLISHED_POLICY_CACHE_ENABLED=env.get("PUBLISHED_POLICY_CACHE_ENABLED", "true"))
# This is explicitly a loopback development launcher, not a production entrypoint.
if args.jar:
    jar = root / "backend/target/gateflow-backend-0.1.0-SNAPSHOT.jar"
    if not jar.is_file():
        raise SystemExit("Build the backend first with Maven verify.")
    java = str(Path(env["JAVA_HOME"]) / "bin/java") if env.get("JAVA_HOME") else "java"
    os.execvpe(java, [java, "-jar", str(jar)], env)
else:
    os.execvpe("mvn", ["mvn", "-f", "backend/pom.xml", "spring-boot:run"], env)
