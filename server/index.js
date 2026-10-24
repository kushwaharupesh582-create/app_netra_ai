const express = require('express');
const app = express();
const http = require('http').Server(app);
const io = require('socket.io')(http);

const PORT = 3000;

app.get('/', (req, res) => {
  res.send('NETRA AI AI Brain & Sync Server is Running');
});

// Socket.io for Real-Time Sync between VI User & Guardian
io.on('connection', (socket) => {
  console.log('A device connected: ' + socket.id);

  // When VI User detects a hazard, broadcast it to Guardians
  socket.on('hazard_detected', (data) => {
    console.log('Hazard Detected:', data);
    // Broadcast to all other connected devices (Guardians)
    socket.broadcast.emit('hazard_detected', data);
  });

  // When VI User moves, broadcast live GPS location to Guardians
  socket.on('live_location', (data) => {
    socket.broadcast.emit('live_location', data);
  });

  // When VI User hits SOS Button
  socket.on('panic_sos', (data) => {
    console.log('CRITICAL: SOS Triggered!', data);
    // Instantly trigger alarm on Guardian devices
    socket.broadcast.emit('panic_sos', data);
  });

  socket.on('disconnect', () => {
    console.log('Device disconnected: ' + socket.id);
  });
});

http.listen(PORT, () => {
  console.log(`NETRA AI Server running at http://localhost:${PORT}/`);
  console.log(`Make sure to connect your Android App to your WiFi IP on port 3000`);
});
