# Confidential worker model artifacts

The confidential worker image expects these files in this directory:

- `pii_ner_v1.onnx`
- `vocab.txt`
- `special_tokens_map.json`

Use an approved, immutable revision of the `dslim/bert-base-NER` model and
verify its provenance before staging it. Do not download a moving branch or
rename an unverified artifact and assume it is approved. The ONNX file's SHA-256
must match `CJAE_ONNX_PII_MODEL_SHA256` in the Docker build and runtime profile.
The tokenizer files are not individually digest-pinned, so they must be
reviewed and shipped from the same immutable model release.

Keep model sources, revisions, and calculated digests in release metadata.
Do not add local command transcripts, account names, user paths, credentials,
or private deployment information to this directory.
