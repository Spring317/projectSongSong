#!/bin/bash
# network_sim.sh

# Default values
INTERFACE="lo"  # Local loopback for testing on same machine
DELAY="0ms"
JITTER="0ms"
LOSS="0%"
BANDWIDTH=""
ACTION="add"

# List available interfaces
echo "Available network interfaces:"
ip -br link show | awk '{print $1}'
echo ""

# Parse command-line options
while getopts "i:d:j:l:b:r" opt; do
  case $opt in
    i) INTERFACE="$OPTARG" ;;
    d) DELAY="$OPTARG" ;;
    j) JITTER="$OPTARG" ;;
    l) LOSS="$OPTARG" ;;
    b) BANDWIDTH="$OPTARG" ;;
    r) ACTION="remove" ;;
    \?) echo "Invalid option -$OPTARG" >&2; exit 1 ;;
  esac
done

# Check if running as root
if [ "$EUID" -ne 0 ]; then
  echo "Please run as root (use sudo)"
  exit 1
fi

# Verify interface exists
if ! ip link show "$INTERFACE" &>/dev/null; then
  echo "Error: Network interface '$INTERFACE' not found."
  echo "Available interfaces:"
  ip -br link show | awk '{print $1}'
  exit 1
fi

# Remove existing traffic control settings
tc qdisc del dev $INTERFACE root 2>/dev/null

# If action is remove, we're done
if [ "$ACTION" = "remove" ]; then
  echo "Network simulation disabled on $INTERFACE"
  exit 0
fi

# Add network delay simulation
tc qdisc add dev $INTERFACE root netem delay $DELAY $JITTER loss $LOSS

# Add bandwidth limitation if specified
if [ -n "$BANDWIDTH" ]; then
  # Remove existing tc and add tbf for bandwidth control
  tc qdisc del dev $INTERFACE root 2>/dev/null
  tc qdisc add dev $INTERFACE root tbf rate $BANDWIDTH burst 32kB latency 400ms
  # Add the delay and packet loss on top of bandwidth control
  tc qdisc add dev $INTERFACE parent 1:1 netem delay $DELAY $JITTER loss $LOSS
fi

echo "Network simulation enabled on $INTERFACE:"
echo "  Delay: $DELAY"
echo "  Jitter: $JITTER"
echo "  Packet Loss: $LOSS"
if [ -n "$BANDWIDTH" ]; then
  echo "  Bandwidth: $BANDWIDTH"
fi