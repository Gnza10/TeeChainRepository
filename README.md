# TeeChain  
## Secure Ethereum Block Signing on Mobile Devices

TeeChain is an Android application that enables mobile devices to operate as secure Ethereum signing nodes by leveraging the **Trusted Execution Environment (TEE)**. The project demonstrates how smartphones can safely perform cryptographic signing tasks while keeping private keys isolated from the operating system.

This repository contains the implementation developed as a **Bachelor’s Thesis (TFG)** in Software Engineering.

---

## Overview

Blockchain networks require secure and reliable signing infrastructure, traditionally provided by dedicated servers or hardware security modules. TeeChain explores an alternative approach: using commodity Android devices as lightweight signing nodes.

By executing sensitive cryptographic operations inside the device’s TEE, TeeChain ensures strong security guarantees while reducing hardware costs and energy consumption. The application is designed to run autonomously, maintaining a persistent connection with an Ethereum node and responding to signing requests without continuous user intervention.

---

## Key Capabilities

- Secure generation and storage of **BLS private keys** inside the TEE  
- Signing of **Ethereum consensus-layer objects** (blocks and attestations)  
- Hardware-backed key isolation using **Android Keystore / HSM**  
- Biometric authentication for access control  
- Encrypted communication with Ethereum nodes  
- Autonomous background operation with fault tolerance  
- Internal logging and activity tracing  

---

## System Architecture

TeeChain follows a **client–node architecture**:

### Android Client
- Developed in **Kotlin**
- Manages networking, authentication, and lifecycle control
- Delegates cryptographic operations to secure hardware

### Trusted Execution Environment (TEE)
- Generates and protects BLS keys
- Performs signing operations
- Prevents key extraction or exposure

### Ethereum Node
- Provides signing requests for blocks and attestations
- Receives signed objects and broadcasts them to the network
- Typically deployed in a local test environment using **Kurtosis**

---

## Security Model

- Private keys are **non-exportable** and never leave the secure environment  
- Signing is performed over the Ethereum-compliant **SSZ signing root**  
- Communication channels are protected using **TLS**  
- Sensitive operations are gated behind **biometric authentication**  
- The system is resilient to temporary network failures and restarts  

---

## Technology Stack

- Android (Kotlin)  
- Ethereum (Proof of Stake – Consensus Layer)  
- BLS signatures (Boneh–Lynn–Shacham)  
- Trusted Execution Environment (TEE)  
- Android Keystore / hardware-backed HSM  
- Kurtosis and Docker for test network deployment  
- GitHub for version control  

---

## Installation and Setup

### Requirements

- Android 10 (API level 29) or higher  
- Physical Android device with TEE support (recommended)  
- Android Studio  
- Docker and Kurtosis for local Ethereum testing  

### Steps

1. Clone the repository:
   ```bash
   git clone https://github.com/Gnza10/TeeChainRepository.git
   ```
2. Open the project in **Android Studio**.

3. Build and deploy the application to a **physical Android device**.

4. Deploy a local Ethereum test network using **Kurtosis**.

5. Configure the Ethereum node **IP address and port** within the application.

---

## Testing

The application has been validated under the following scenarios:

- Continuous signing of blocks and attestations  
- Network interruptions and automatic reconnection  
- Background and foreground lifecycle transitions  
- Invalid input and transmission errors  
- Long-running autonomous execution  

---

## Results

- Correct generation and validation of Ethereum-compatible **BLS signatures**  
- Secure isolation of cryptographic material  
- Stable performance with **sub-second signing latency** under normal conditions  
- Reliable operation during extended execution periods  

---

## Future Work

- Support for multiple validator keys  
- Remote attestation of the secure environment  
- Compatibility with production Ethereum networks  
- iOS implementation using **Secure Enclave**  
- Power consumption optimization strategies  

---

## Author

**Gonzalo Muñoz Rubio**  
Bachelor’s Thesis in Software Engineering  
University of Málaga — 2025  

