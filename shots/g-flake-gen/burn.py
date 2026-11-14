import multiprocessing as mp
import sys
import time


def burn(seconds):
    end = time.time() + seconds
    x = 0
    while time.time() < end:
        x = (x * 1103515245 + 12345) & 0x7FFFFFFF
    return x


if __name__ == "__main__":
    secs = float(sys.argv[1])
    procs = int(sys.argv[2])
    ps = [mp.Process(target=burn, args=(secs,)) for _ in range(procs)]
    for p in ps:
        p.start()
    for p in ps:
        p.join()
    print("burn done", secs, "s", procs, "procs")
