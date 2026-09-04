.PHONY: docker-build build test check toolchain benchmark-validate

docker-build:
	docker compose build android-build

build:
	docker compose run --rm android-build build

test:
	docker compose run --rm android-build check

check:
	docker compose run --rm android-build check

toolchain:
	docker compose run --rm android-build toolchainInfo

benchmark-validate:
	docker compose run --rm android-build validateBenchmarkProfiles testBenchmarkProfileValidation testBenchmarkRecorder validateNativeCompatibilityGate
