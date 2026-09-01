# Patient Management System

Microservices-based patient management platform built with Spring Boot, Spring Cloud Gateway, gRPC, Kafka, PostgreSQL, and Docker.

## Overview

This repository contains a set of services that work together to manage patients, authentication, billing, and analytics:

- `auth-service` handles login and JWT validation.
- `patient-service` manages patient CRUD operations and publishes patient events.
- `billing-service` exposes a gRPC API used by the patient service.
- `analytics-service` consumes patient events from Kafka.
- `api-gateway` routes incoming requests and applies JWT validation on protected endpoints.
- `infrastructure` contains AWS CDK / LocalStack setup for local cloud emulation.
- `integration-tests` contains end-to-end tests for the services.

## Architecture

- API traffic enters through `api-gateway` on port `4004`.
- Authentication requests are routed to `auth-service` on port `4005`.
- Patient CRUD requests are routed to `patient-service` on port `4000`.
- `patient-service` calls `billing-service` over gRPC on port `9001`.
- `patient-service` publishes patient events to Kafka.
- `analytics-service` consumes those events from Kafka.

## Tech Stack

- Java 21
- Spring Boot
- Spring Cloud Gateway
- Spring Security + JWT
- gRPC
- Kafka
- PostgreSQL
- Docker and Docker Compose
- AWS CDK / LocalStack for infrastructure emulation

## Prerequisites

- Java 21
- Maven 3.9+ for modules without a Maven Wrapper
- Docker and Docker Compose
- Optional: AWS CLI if you want to deploy the LocalStack stack

## Repository Structure

- `auth-service/`
- `patient-service/`
- `billing-service/`
- `analytics-service/`
- `api-gateway/`
- `infrastructure/`
- `integration-tests/`
- `api-requests/`
- `grpc-requests/`

## Local Run with Docker Compose

The simplest way to start the system locally is with Docker Compose.

```bash
docker compose up --build
```

This starts:

- PostgreSQL for auth service on host port `5001`
- PostgreSQL for patient service on host port `5432`
- Kafka on ports `9092` and `9094`
- `auth-service` on `4005`
- `patient-service` on `4000`
- `billing-service` on `4001` and `9001`
- `analytics-service` on `4002`
- `api-gateway` on `4004`

## Service Ports

- `auth-service`: `4005`
- `patient-service`: `4000`
- `billing-service`: `4001` HTTP, `9001` gRPC
- `analytics-service`: `4002`
- `api-gateway`: `4004`
- `kafka`: `9092` internal, `9094` external

## Environment Variables

The repository includes an example env file:

- `.env.example`

Currently it contains:

- `LOCALSTACK_AUTH_TOKEN`

If you use the LocalStack infrastructure, fill in the required values before deploying.

## API Usage

Example request files are available under:

- `api-requests/auth-service/`
- `api-requests/patient-service/`
- `grpc-requests/billing-service/`

These can be used to test the endpoints locally.

## Authentication

The auth service issues JWTs, and the gateway validates them before forwarding protected patient-service requests.

Typical flow:

1. Call the auth login endpoint.
2. Copy the returned JWT.
3. Send the token with requests to patient endpoints through the gateway.

## Infrastructure / LocalStack

The `infrastructure` module contains CDK code for a LocalStack-based deployment. The helper script expects a generated CDK output file:

```bash
aws --endpoint-url=http://localhost:4566 cloudformation deploy \
  --stack-name patient-management \
  --template-file "./cdk.out/localstack.template.json"
```

If you use this path, make sure the CDK synth step has already produced `cdk.out/localstack.template.json`.

## Running Tests

Each service has its own Maven build. Example:

```bash
cd auth-service
./mvnw test
```

On Windows:

```powershell
cd auth-service
.\mvnw.cmd test
```

The `integration-tests` module contains cross-service tests.

## Notes

- The repository is organized as separate service modules, not a single parent Maven project at the root.
- Service configuration is mostly stored in each module's `src/main/resources/application.properties` or `application.yml`.
- The request files in `api-requests/` and `grpc-requests/` are useful for manual testing and quick smoke checks.

## Future Improvements

- Add centralized configuration management.
- Add service-level observability and distributed tracing.
- Add stronger production security configuration.
- Add automated deployment to a cloud environment.
- Expand integration test coverage.
