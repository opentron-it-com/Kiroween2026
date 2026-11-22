

HISTORICAL CONFIDENTIAL JAVA AI ENGINE (CJAE) ARCHITECTURE SPECIFICATION


SUPPORTED PROVIDER SCOPE: GOOGLE CLOUD CONFIDENTIAL SPACE AND AWS NITRO
This is a historical aspirational design, not an implementation specification.
References to `/dev/sev-guest`, `org.cjae.engine`, GraalVM native-image output,
and exclusive off-heap processing describe concepts that are not implemented
by the current worker. For verified code, configuration, and deployment status,
use the backend README and source tree.


1. PURPOSE AND PARADIGM

The CONFIDENTIAL JAVA AI ENGINE (CJAE) is a production-grade, type-safe, high-concurrency architecture that implements specialized Confidential AI capabilities natively on the Java Virtual Machine (JVM). CJAE acts as a secure, zero-trust overlay for enterprise AI workloads. Its primary objective is to guarantee end-to-end data privacy by ensuring that sensitive data, LLM weights, tokenization maps, and vector arrays remain fully encrypted even during active computation.
By executing directly inside hardware-isolated Trusted Execution Environments (TEEs), CJAE isolates data in use from host operators, cloud providers, and guest OS vulnerabilities. Unlike Python-based confidential computing layers that suffer from high serialization latency, CJAE weaponizes modern JVM capabilities to deliver hardware-enforced privacy at native execution speeds.

2. ARCHITECTURAL VALIDATION & THE BEST APPROACH

To ensure this specification translates into a secure, working implementation, the architectural design explicitly addresses known hardware and runtime constraints through the following engineering approaches:
• The TEE Boundary Approach: Project Panama and MemorySegment do not natively modify the underlying hardware state or auto-create enclaves. Therefore, CJAE does not rely on Java to spin up the TEE. The best approach is an Infrastructure-First deployment model, where the entire JVM/GraalVM binary is booted directly inside a pre-configured hardware-encrypted environment managed by cloud-specific orchestrators. Java code interacts with this existing environment strictly via file descriptors, virtual sockets, and verified native libraries.
• The Hardware Abstraction Approach: AMD SEV-SNP (which encrypts an entire virtual machine), Intel SGX/TDX, and AWS Nitro Enclaves utilize entirely different execution, driver, and attestation models. One generic native binding cannot implement them all. The best approach is a pluggable Service Provider Interface (SPI) pattern. CJAE decouples the core engine from the cloud hardware layer by defining clean, abstract interfaces for attestation, transport, and key release. Cloud-specific provider modules are compiled as decoupled adapters and loaded dynamically based on the active deployment environment profile.
• The Execution Guard Approach: The @ConfidentialAI annotation cannot automatically warp a thread into a TEE or safely parse arbitrary, deeply nested object arguments. The best approach is an Isolated Gateway Architecture. @ConfidentialAI is used purely as an execution guard to intercept calls, block unauthorized states, and enforce a fail-closed policy. It passes execution to explicit, low-level service boundaries handling flat primitive data types (like byte arrays and float arrays) that can be predictably monitored, mapped, and scrubbed.
• The Identity & Multi-Tenancy Approach: Managing raw database-bound tenant API keys inside the confidential worker violates zero-trust principles and increases state bloat within the TEE. The best approach is an OIDC/JWT Token Validation & Decoupled Architecture. CJAE leverages signed OIDC bearer tokens containing cryptographically verified multi-tenant context, scopes, and quota boundaries. The engine extracts these attributes off-heap using standard verification keys (JWKS), completely decoupling the TEE worker from central identity databases while enforcing hard data isolation.
• The Compilation Compatibility Approach: Ahead-of-Time (AOT) compilation via GraalVM Native Image, dynamic JNI behavior within the ONNX Runtime, and hardware-enforced TEE constraints introduce severe runtime stability risks if unverified. The best approach is a Rigid Automated Validation Pipeline. The build pipeline executes a mandatory conformance test suite inside real target cloud TEE instances prior to release approval, ensuring reflection configurations (reflect-config.json) and shared native libraries are fully mapped and stable.
• The Memory & Zeroization Approach: Using Virtual Threads, Project Panama, and off-heap allocations does not inherently guarantee zero-copy performance or safe data destruction. Plaintext leaks can occur if arrays are duplicated on the heap during standard object transitions. The best approach is Mandated Off-Heap Lifecycle Management. CJAE restricts sensitive data structures exclusively to Panama-allocated MemorySegment blocks. These blocks are guarded by strict try-with-resources blocks that execute explicit hardware-accelerated memory-zeroing loops (internalSegment.fill((byte) 0)) immediately upon thread completion or process failure.

3. SUPPORTED PROVIDER ARCHITECTURAL MATRIX

To achieve true cross-cloud deployment without polluting the core AI pipelines, CJAE splits its attestation and transport boundary into isolated, environment-driven modules.
                  +-------------------------------------------------+

                  |      CJAE Core / @ConfidentialAI Aspect Proxy   |
                  +------------------------|------------------------+
                                           | (SPI Decoupling)
                                           v
      +------------------------------------+------------------------------------+

      |                                    |
      v                                    v
+----------------------------+     +----------------------------+
|      GCP CVM Adapter       |     |     AWS Nitro Adapter      |
|  - Confidential Space      |     |  - Nitro Enclave           |
|  - Auth: GCA / WIF / KMS   |     |  - Transport: Vsock IPC    |
+----------------------------+     +----------------------------+

3.1 Google Cloud Confidential Space Module

• Isolation Paradigm: A measured container workload running on Google Confidential Space.
• Evidence Source: The worker obtains GCA evidence through the packaged `gotpm` utility and guest vTPM/event log.
• Attestation Mechanism: GCA JWT claims are checked against the configured nonce, audience, hardware model, VM identity, and Confidential Space image digest; clients can independently verify a nonce-bound attestation response.
• Key Release Boundary: Workload Identity Federation exchanges accepted GCA evidence for short-lived credentials used to decrypt the context key with Cloud KMS. Cloud-side WIF and KMS policies must independently enforce the workload claims.

3.2 AWS Nitro Enclave Module (cjae-attestation-aws-nitro)

• Isolation Paradigm: CPU and Memory isolation micro-VM. It features an completely isolated environment with no persistent disk, no external internet ingress, and no traditional OS stack.
• Panama Binding Target: Uses Java Foreign Function & Memory downcalls to the Linux NSM device at `/dev/nsm`.
• Transport Mechanism: Since typical TCP/HTTP networking is blocked inside the enclave, the worker communicates with a parent-instance KMS proxy over vsock.
• Attestation & Key Release Boundary: The worker creates an NSM attestation document bound to a nonce and recipient key. The proxy forwards the KMS `Decrypt` recipient request; AWS KMS evaluates the configured image/PCR policy and the key is unwrapped inside the enclave.

4. THREE-STEP IMPLEMENTATION PIPELINE


STEP 1: THE TEE BINDING (NATIVE HARDWARE ACCESS)

CJAE bypasses the legacy Java Native Interface (JNI) to eliminate translation layer latency. It uses Project Panama (Foreign Function & Memory API) to build a lightweight, type-safe Java wrapper directly around target cloud drivers (/dev/sev-guest) or proprietary enclave libraries (libnsm.so).
By leveraging the Panama Linker and SymbolLookup subsystems, the engine maps native memory pointers and loads target hardware guest device file descriptors or libraries natively. This allows Java applications to execute low-level hardware operations—such as allocating encrypted pages, fetching unforgeable platform hashes, and running vector math over TEE-protected caches—at near-native C-speeds.
+------------------------------------------------------------+

|             Confidential Java AI Engine Runtime            |
|  [Arena Memory]  --->  [Panama FFM API MemorySegment]      |
+-------------------------------|----------------------------+
                                | (Zero-Copy SPI Dispatch)
                                v
     +--------------------------+--------------------------+

     | (Confidential VMs)                                  | (AWS Nitro Enclaves)
     v                                                     v
+------------------------------------+             +------------------------------+

|   Kernel Guest Driver Interface    |             |  Nitro Secure Module Library |
| [/dev/sev-guest] / [/dev/tdx-guest]|             |        [libnsm.so via Panama]|
+------------------------------------+             +------------------------------+

STEP 2: MICRO-FRAMEWORK INTEGRATION (DECLARATIVE MIDDLEWARE)

CJAE provides a secure middleware layer designed to snap seamlessly into enterprise ecosystems like Spring AI or LangChain4j. Instead of requiring developers to write low-level cryptographic code or establish standalone Python sidecars, CJAE introduces a declarative, proxy-interceptable annotation: @ConfidentialAI.
When applied to a Spring Boot service, repository layer, or LangChain4j tool agent, a Spring AOP proxy intercepts execution. The aspect instantly handles prompt tokenization, routes the specific tensor payloads to off-heap Arena memory, and processes the AI logic within the secure hardware enclave. If the underlying hardware verifier detects an expired lease or unauthenticated state, the middleware triggers a fail-closed execution halt before any data can leak to the host.
[ Incoming Application Request ]
               |
               v
  @ConfidentialAI Aspect Proxy
               |
      (Fails Closed if Unverified or Multi-Tenant Token Fails Validation)
               v
+--------------------------------------------+

|        Spring AI / LangChain4j Context     |
|  - Local NLP Tokenizer Masks Entities      |
|  - Off-Heap Memory Transmitted to Enclave  |
+--------------------------------------------+

STEP 3: ATTESTATION ENGINE (JAVA-NATIVE CRYPTOGRAPHIC VERIFICATION)

CJAE features a built-in, Java-native cryptographic attestation service designed to build complete client-side trust. When an external client application initiates a nonce challenge or transmits sensitive payloads, the Attestation Engine queries the underlying hardware layer established in Step 1 to extract raw, unforgeable hardware measurements.
The engine processes raw evidence against provider-specific trust roots (GCP Confidential Computing and AWS KMS). Attestation claims are provider- and workload-specific; they do not by themselves prove that application code is untampered or that host intervention is impossible. The deployed client challenge flow and its limits are documented in the backend README.
Client App                   CJAE Gateway / TEE              Attestation Provider

    |                               |                                |
    |----- 1. Nonce Challenge ----->|                                |
    |                               |----- 2. Query Driver/NSM ----->|
    |                               |<---- 3. Raw Evidence Report ---|
    |                               |                                |
    |                               |----- 4. Submit Evidence ------>|
    |                               |<---- 5. Signed Policy Claims --|
    |<---- 6. Attestation Bundle ---|                                |

5. AOT COMPILATION & ENCLAVE DEPLOYMENT MATRIX

To fit within the memory-constrained bounds of hardware enclaves, the application footprint is stripped of standard dynamic JVM components. The system uses GraalVM Native Image to compile the code ahead-of-time (AOT) into a standalone native binary that eliminates reflection, dynamic class loading, and standard JVM management bloat.

Maven Profile Build Configuration (pom.xml)

xml
<profile>
    <id>native-confidential</id>
    <build>
        <plugins>
            <plugin>
                <groupId>org.graalvm.buildtools</groupId>
                <artifactId>native-maven-plugin</artifactId>
                <version>0.10.1</version>
                <extensions>true</extensions>
                <configuration>
                    <imageName>confidential-java-ai-worker</imageName>
                    <mainClass>org.cjae.engine.MainApplication</mainClass>
                    <buildArgs>
                        <!-- Permit runtime native execution for Project Panama hooks -->
                        <buildArg>--enable-native-access=org.cjae.engine</buildArg>
                        <!-- Strip JVM fallback modes to guarantee a strict zero-dependency binary -->
                        <buildArg>--no-fallback</buildArg>
                        <!-- Enforce maximum optimization for execution speed and memory limits -->
                        <buildArg>-O3</buildArg>
                        <buildArg>-H:+AllowVMParsing</buildArg>
                    </buildArgs>
                </configuration>
            </plugin>
        </plugins>
    </build>
</profile>
Use code with caution.

Ahead-of-Time JNI Metadata Target (reflect-config.json)

This configuration explicitly preserves the necessary JNI structures required by the ONNX Runtime Java API, ensuring they are not stripped away by GraalVM dead-code elimination loops:
json
[
  {
    "name": "ai.onnxruntime.OrtEnvironment",
    "allDeclaredConstructors": true,
    "allPublicMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "ai.onnxruntime.OrtSession",
    "allDeclaredConstructors": true,
    "allPublicMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "ai.onnxruntime.OnnxTensor",
    "allDeclaredConstructors": true,
    "allPublicMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "java.nio.DirectByteBuffer",
    "allDeclaredConstructors": true,
    "allPublicMethods": true,
    "allDeclaredFields": true
  }
]
Use code with caution.

6. THREAT MODELING AND RIGID CONTROLS


Threat Vector	CJAE Architectural Control Pattern	Residual System Risk
Host Operator / Hypervisor Memory Snoop	Strict hardware-level cache and page table encryption (SEV-SNP, TDX, Nitro isolation) masks runtime data dynamically.	Speculative execution bugs or side-channel exploits in CPU microcode architectures.
Tampered / Compromised System Workloads	Attestation Engine binds image measurements to the hardware boot sequence, blocking key release on policy mismatch.	Pre-compilation supply chain vulnerabilities or malicious third-party open-source dependencies.
JVM Heap Dumps / Core File Extraction	Hard migration of all active AI weights, contexts, and arrays out of the JVM heap into Panama off-heap storage.	Unmanaged native code running with broad process clearance within the same enclave boundary.
Cross-Tenant Context Contamination	Multi-tenant OIDC JWT context parsing enforces structural runtime segmentation across executing context handles.	Tenant configuration flaws or logical token signing exploit profiles.
Concurrency Latency Spikes / Heap Spills	Project Loom virtual threads pair with GC-immune Arena layouts, keeping latency charts flat under load.	Delayed execution triggers or un-scrubbed native buffers if an abrupt process panic occurs.
