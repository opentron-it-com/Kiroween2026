import importlib.util
import unittest
from pathlib import Path

SCRIPT = (
    Path(__file__).parents[2]
    / "java"
    / "opentron-java"
    / "confidential-worker"
    / "src"
    / "main"
    / "resources"
    / "benchmark"
    / "python_serialized_proxy.py"
)
SPEC = importlib.util.spec_from_file_location("python_serialized_proxy", SCRIPT)
PROXY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROXY)


class PythonSerializedProxyTest(unittest.TestCase):
    def test_synthetic_rag_pipeline_matches_java_off_heap_golden(self):
        record_ids, prompt_sha256, serialized_bytes, prompt, matches = (
            PROXY.run_pipeline(
                1_000,
                "segment-7 region-11 product-42",
                10,
                100,
            )
        )

        self.assertEqual(
            [420, 472, 203, 433, 906, 929, 3, 29, 96, 98],
            record_ids,
        )
        self.assertEqual(
            "a5486132aa29ab9211317fc3a33378eea29e072ed12faf4e6553320522acdc00",
            prompt_sha256,
        )
        self.assertGreater(serialized_bytes, 0)
        self.assertTrue(prompt.startswith("query=segment-7 region-11 product-42\n"))
        self.assertEqual(record_ids, [match["recordId"] for match in matches])


if __name__ == "__main__":
    unittest.main()
