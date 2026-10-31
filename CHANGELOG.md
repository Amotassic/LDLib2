## v2.2.45
* Added `MarkStrategies`, a registry of cheap ways to tell whether values of a class changed, and `IContentVersion` for values that count their own changes
* Added `UIRecording`, scripted UI videos with a smooth pointer, click and key overlays, captions and zooms, run with `-PldRecord`
* Added a gui scale of their own to OS windows, picked by right-clicking a floated tab or from View > Window Size
* Improved async sync to send one packet per chunk each period, and to leave the changes of chunks nobody watches unencoded until someone does, while still saving them
* Improved async sync to call `setChanged` once per block entity per period instead of once per changed field
* Improved change detection of managed fields, comparing item and fluid containers slot by slot instead of serializing them every period, comparing collections and maps of strings, numbers, enums and registry entries directly, and encoding codec marked fields once instead of twice when they change
* Improved attaching and detaching async logics to constant time, so unloading many block entities at once no longer stalls the server
* Fixed custom persisted data of managed holders never loading back
* Fixed null elements of managed lists and sets loading back as `_NULL_` or as a copy of the element before them
* Fixed item and fluid stack marks sharing component maps with the live stacks
* Fixed sync holders without RPC never leaving the async thread
* Fixed OS windows failing to open on macOS
* Fixed closing an OS window leaving its UI alive, which leaked it and skipped the editor's teardown
* Fixed an editor popped out from code being pulled back into the game window by its settings
* Fixed the UI editor losing JEI, REI and EMI after docking back from its own window
* Fixed adaptive scrollers laid out empty never growing, which hid floating windows' tab strips
* Fixed picking a context menu entry leaving focus on the closed menu, so shortcuts did nothing afterwards
* Fixed the scene cache compile throwing when its scene closed mid-compile, and leaking native buffers
