# keep2memos

Import Google Keep notes to [memos](https://www.usememos.com/).

## Steps

1. Install Java version 21 or newer, if you don't have it.
2. Export an archive of Google Keep content using [Google Takeout](https://takeout.google.com/).
3. Download the latest [release](https://github.com/joshefin/keep2memos/releases) of keep2memos (a _jar_ file).
4. Extract the Google Takeout archive to any directory and save the path to the Keep subdirectory.
5. Open memos in your browser, go to _Settings > My Account_ and create a new Access token.
6. Run keep2memos in your Terminal/Command Prompt like in the following example:

```bash
java -jar keep2memos.jar --keep-dir /mnt/c/Downloads/takeout-20250101T010101Z-001/Takeout/Keep/ --memos-url http://localhost:5230/ --memos-token abcd1234
```

Use the correct path to a Google Takeout Keep directory, URL of the memos instance and the previously created access token.

> [!TIP]
> Maybe first try a Keep directory with just a few notes, so you can test the import process and see if you like the results.