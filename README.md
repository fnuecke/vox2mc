# vox2mc

Turns a [MagicaVoxel](https://ephtracy.github.io/) `.vox` file into a Minecraft block model and the texture that goes with it.

The focus is to generate as few quads as possible. While we're using a greedy search, for typical inputs we are close to optimal (within a few percent of theoretical optimal).

Models must be exactly 16x16x16 voxels. The MagicaVoxel scene graph is ignored.

If you're looking for an editor, consider [Goxel](https://github.com/guillaumechereau/goxel).

## Usage

```
./gradlew installDist
./build/install/vox2mc/bin/vox2mc -m mymod -o src/main/resources/assets model.vox
```

writes `<output>/<modid>/models/block/model.json` and `<output>/<modid>/textures/block/model.png`.

Run with `--help` for options (noise, gradient, ...).
