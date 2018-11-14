# README for SER_Reader

Version: 0.2 (2018-11-13, 13:35 mmohn)


## Copyright

(c) 2018 Michael Mohn, Ulm University

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.
You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.


## Features

### Special features for image stacks larger than the computer's memory:

- limit range of slices to read, e.g. only 100th to 500th slice
- read large image stacks with increment > 1, e.g. every 2nd image

### Features for image stacks:

- recording time is copied to the slice metadata of the image slice.
(Date and time in the time zone of the computer that runs ImageJ).
Unfortunately, the precision is only 1 second.


## Installation

In order to use this reader plugin via the ImageJ open dialog or
via drag & drop, the plugin should be installed in the Input-Output
folder (ImageJ/plugins/Input-Output) and the HandleExtraFileTypes
plugin has to be modified, too. I added the following lines to the
"HandleExtraFileTypes.java" file in the Input-Output folder:

		// TIA ser files (SER_Reader)
		// --------------------------
		if (name.endsWith(".ser")) {
            return tryPlugIn("SER_Reader", path);
		}

Please remember to compile both the SER_Reader plugin and the
HandleExtraFileTypes plugin.


## Known issues:

- calibration is read once for 1st slice, assuming same calibration
    for all slices. Only width, height and image type will be checked
    for other slices.
- 1D data is not supported (spectra)!
- reader doesn't check whether file is too large for memory

## Changelog:

### Version 0.2

- New feature: conversion to 32-bit grayscale (default, can be deselected in GUI)