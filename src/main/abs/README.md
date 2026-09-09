These files originated at
https://github.com/BedreFlyt/bedreflyt/tree/main/ABS.  Moved here to
fix https://github.com/BedreFlyt/bedreflyt/issues/30 without checking
in the generated jar file.

ABS compilation to the Java backend:

```sh
absc --java bedreflyt.abs -o bedreflyt.jar
```

Execution of the jar:

```sh
java -jar bedreflyt.jar
```
