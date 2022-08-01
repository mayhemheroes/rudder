// SPDX-License-Identifier: GPL-3.0-or-later WITH GPL-3.0-linking-source-exception
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

#![no_main]
#[macro_use]
extern crate libfuzzer_sys;
extern crate rudder_relayd;

use rudder_relayd::data::RunInfo;

fuzz_target!(|data: &str| {
    let _ = data.parse::<RunInfo>();
});
