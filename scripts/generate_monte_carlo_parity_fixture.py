"""Write reference Monte Carlo results from the pinned FE engine to stdout.

Run from BE: python3 scripts/generate_monte_carlo_parity_fixture.py /path/to/FE
"""

import argparse
import json
import subprocess
import sys
from copy import deepcopy
from dataclasses import replace
from pathlib import Path


FE_COMMIT = "bd73c5c63ce0aace6cdf06f5ef2a9accfefe8ac2"


def java_config(config):
    simulation = config["simulation"]
    investment = config["investment"]
    uncertain = config["uncertain"]
    return {
        "simulation": {
            "nPaths": simulation["n_paths"],
            "seed": simulation["seed"],
            "meanBlockLength": simulation["mean_block_length"],
            "jumpProbPerMonth": simulation["jump_prob_per_month"],
            "jumpSizeTri": simulation["jump_size_tri"],
        },
        "investment": {
            "gpus": investment["gpus"],
            "months": investment["months"],
            "capexPerGpu": investment["capex_per_gpu"],
            "rampMonths": investment["ramp_months"],
            "residualValuePct": investment["residual_value_pct"],
            "pricingMode": investment["pricing_mode"].upper(),
        },
        "uncertain": {
            "utilization": uncertain["utilization"],
            "capexOverrun": uncertain["capex_overrun"],
            "opexPerGpuMonth": uncertain["opex_per_gpu_month"],
            "opexAnnualInflation": uncertain["opex_annual_inflation"],
        },
        "targetIrrAnnual": config["target_irr_annual"],
        "candidatePrices": config["candidate_prices"],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fe_root", type=Path, help="checkout of the pinned FE reference revision")
    args = parser.parse_args()
    fe_root = args.fe_root.resolve()
    git = ["git", "-C", str(fe_root)]
    revision = subprocess.check_output([*git, "rev-parse", "HEAD"], text=True).strip()
    reference_status = subprocess.check_output(
        [*git, "status", "--porcelain", "--", "montecarlo/engine"], text=True
    )
    if revision != FE_COMMIT or reference_status.strip():
        parser.error(f"a clean FE engine at {FE_COMMIT} is required")

    sys.dont_write_bytecode = True
    engine = fe_root / "montecarlo" / "engine"
    sys.path.insert(0, str(engine))
    from price_paths import load_history, log_returns
    from pricing_range import (
        draw_scenarios, generate_paths, market_ranges, price_range, scenario_comparison, select_paths
    )

    config = json.loads((engine / "config_sample.json").read_text(encoding="utf-8"))
    config["simulation"]["n_paths"] = 21
    config["investment"]["months"] = 16
    config["candidate_prices"] = [0.01, 5.0, 7.0, 9.0, 12.25, 20.0]
    _, prices = load_history(engine / "sample_price_history.csv")
    paths = generate_paths(config, log_returns(prices), "ease")
    samples = draw_scenarios(config)

    cases = []
    for mode, scenario, market_price in [("follow_market", "기준", None), ("fixed", None, 0.01)]:
        case = deepcopy(config)
        case["investment"]["pricing_mode"] = mode
        if mode == "fixed":
            case["investment"]["residual_value_pct"] = 0
        case_samples = [
            replace(sample, pricing_mode=mode, residual_value_pct=case["investment"]["residual_value_pct"])
            for sample in samples
        ]
        indices, selection_info = select_paths(paths, prices[-1], scenario, market_price)
        expected = price_range(case, paths, case_samples, indices)
        comparisons = scenario_comparison(case, paths, case_samples, prices[-1])
        for row in comparisons:
            _, info = select_paths(paths, prices[-1], scenario=row["scenario"])
            row["n_selected"] = info["n_selected"]
        cases.append({
            "config": java_config(case),
            "selection": {
                "scenario": "BASELINE" if scenario else None,
                "marketPrice12m": market_price,
                "post12Trend": "EASE",
            },
            "expected": {
                "selection": selection_info,
                "requiredQuantiles": expected["required_quantiles"],
                "candidates": expected["candidates"],
                "baseRequired": expected["base_required"],
                "sensitivity": expected["tornado"],
                "scenarioComparison": comparisons,
            },
        })

    fixture = {
        "feCommit": FE_COMMIT,
        "lastPrice": prices[-1],
        "paths": paths,
        "sampleInputs": [
            {
                "utilization": sample.utilization,
                "capexOverrun": sample.capex_overrun,
                "opexPerGpuMonth": sample.opex_per_gpu_month,
                "opexAnnualInflation": sample.opex_annual_inflation,
            }
            for sample in samples
        ],
        "monthlyRanges": market_ranges(paths, prices[-1], range(1, 13)),
        "pricingCases": cases,
    }
    print("{")
    entries = list(fixture.items())
    for index, (key, value) in enumerate(entries):
        suffix = "," if index < len(entries) - 1 else ""
        if key in ("paths", "sampleInputs", "monthlyRanges"):
            rows = ",\n".join(f"    {json.dumps(row, allow_nan=False)}" for row in value)
            print(f'  "{key}": [\n{rows}\n  ]{suffix}')
        else:
            encoded = json.dumps(value, indent=2, allow_nan=False).replace("\n", "\n  ")
            print(f'  "{key}": {encoded}{suffix}')
    print("}")


if __name__ == "__main__":
    main()
