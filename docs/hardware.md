# DontFall hardware

Board: STM32MP157C-DK2.

## Pin reference

For every pin question, the source of truth is ST's GPIO mapping for the
DKx boards:
<https://wiki.st.com/stm32mpu/wiki/STM32MP157x-DKx_-_hardware_description#GPIO_mapping>

Before using a pin, check there that it is routed to a header (Arduino or
GPIO expansion) and not already used by something on the board. Don't guess
pins from memory.

The tables below are transcribed from that page. If they disagree with it,
the ST page wins.

### GPIO expansion connector (CN2)

| Function | STM32 pin | Pin | Pin | STM32 pin | Function |
|----------|-----------|-----|-----|-----------|----------|
| 3V3 | - | 1 | 2 | - | 5V |
| GPIO2 / I2C5_SDA | PA12 | 3 | 4 | - | 5V |
| GPIO3 / I2C5_SCL | PA11 | 5 | 6 | - | GND |
| GPIO4 / MCO1 | PA8 | 7 | 8 | PB10 | GPIO14 / USART3_TX |
| GND | - | 9 | 10 | PB12 | GPIO15 / USART3_RX |
| GPIO17 / USART3_RTS | PG8 | 11 | 12 | PI5 | GPIO18 / SAI2_SCKA (1) |
| GPIO27 / SDMMC3_D3 | PD7 | 13 | 14 | - | GND |
| GPIO22 / SDMMC3_CK | PG15 | 15 | 16 | PF1 | GPIO23 / SDMMC3_CMD |
| 3V3 | - | 17 | 18 | PF0 | GPIO24 / SDMMC3_D0 |
| GPIO10 / SPI5_MOSI | PF9 | 19 | 20 | - | GND |
| GPIO9 / SPI5_MISO | PF8 | 21 | 22 | PF4 | GPIO25 / SDMMC3_D1 |
| GPIO11 / SPI5_SCK | PF7 | 23 | 24 | PF6 | GPIO8 / SPI5_NSS |
| GND | - | 25 | 26 | PF3 | GPIO7 |
| I2C1_SDA (2) | PF15 | 27 | 28 | PD12 | I2C1_SCL (2) |
| GPIO5 / MCO2 | PG2 | 29 | 30 | - | GND |
| GPIO6 / TIM5_CH2 | PH11 | 31 | 32 | PD13 | GPIO12 / TIM4_CH2 |
| GPIO13 / TIM3_CH2 | PC7 | 33 | 34 | - | GND |
| GPIO19 / SAI2_FSA (1) | PI7 | 35 | 36 | PB13 | GPIO16 / USART3_CTS |
| GPIO26 / SDMMC3_D2 | PF5 | 37 | 38 | PI6 | GPIO20 / SAI2_SDA (1) |
| GND | - | 39 | 40 | PF11 | GPIO21 / SAI2_SDB (1) |

1. SAI2 is shared with the audio codec. By default it goes to the codec
   (solder bridges SB13-SB16); see the DKx user manual.
2. I2C1 also talks to several on-board peripherals. Anything added to it
   must not disturb that bus.

### Arduino Uno connector

CN13 (10x1):

| Pin | Signal | STM32 pin | Comment |
|-----|--------|-----------|---------|
| 1 | ARD_D8 | PG3 | IO |
| 2 | ARD_D9 | PH6 | TIM12_CH1 |
| 3 | ARD_D10 | PE11 | SPI4_NSS and TIM1_CH2 |
| 4 | ARD_D11 | PE14 | SPI4_MOSI and TIM1_CH4 |
| 5 | ARD_D12 | PE13 | SPI4_MISO |
| 6 | ARD_D13 | PE12 | SPI4_SCK |
| 7 | GND | - | GND |
| 8 | VREFP | - | VREF+ |
| 9 | ARD_D14 | PA12 | I2C5_SDA |
| 10 | ARD_D15 | PA11 | I2C5_SCL |

CN14 (8x1):

| Pin | Signal | STM32 pin | Comment |
|-----|--------|-----------|---------|
| 1 | ARD_D0 | PE7 | UART7_RX |
| 2 | ARD_D1 | PE8 | UART7_TX |
| 3 | ARD_D2 | PE1 | IO |
| 4 | ARD_D3 | PD14 | TIM4_CH3 |
| 5 | ARD_D4 | PE10 | IO |
| 6 | ARD_D5 | PD15 | TIM4_CH4 |
| 7 | ARD_D6 | PE9 | TIM1_CH1 |
| 8 | ARD_D7 | PD1 | IO |

CN16 (8x1, power):

| Pin | Signal | Comment |
|-----|--------|---------|
| 1 | NC | reserved for test |
| 2 | 3V3 | IOREF 3V3 |
| 3 | NRST | NRST |
| 4 | 3V3 | 3V3 |
| 5 | 5V | 5V |
| 6 | GND | GND |
| 7 | GND | GND |
| 8 | VIN | not connected |

CN17 (6x1, analog):

| Pin | Signal | STM32 pin | Comment |
|-----|--------|-----------|---------|
| 1 | A0 | PF14 | ADC1_IN0 |
| 2 | A1 | PF13 | ADC1_IN1 |
| 3 | A2 | ANA0 | ADC1_IN6 |
| 4 | A3 | ANA1 | ADC1_IN2 |
| 5 | A4 | PC3 / PA12 | ADC1_IN13 |
| 6 | A5 | PF12 / PA11 | |

PA12/PA11 (I2C5) are on CN2 pins 3/5 and ARD_D14/D15, and are also listed
as an option for A4/A5. Those are the same pins, so check the DKx user manual
for how A4/A5 are routed before using them alongside I2C5.

### User buttons and LEDs

| LED | Button | GPIO |
|-----|--------|------|
| Green LD5 (3) | USER1 (3) | PA14 |
| Red LD6 (3) | USER2 (3) | PA13 |
| Orange LD7 | - | PH7 |
| Blue LD8 | - | PD11 |

3. The LED and the button share the GPIO. The LED is active low (it lights
   when the GPIO output is low).

Some user LEDs show system activity by default; see ST's "LEDs and buttons
on STM32 MPU boards" article before taking one over.

## Pin assignments

Every pin the project uses goes in this table, in the same change that
starts using it. "Core" is the core that owns the peripheral: M4 (Zephyr)
or A7 (Linux).

| Signal | SoC pin | Header pin | Peripheral / AF | Core | Notes |
|--------|---------|------------|-----------------|------|-------|
| _none assigned yet_ | | | | | |

## Parts

| Part | Status |
|------|--------|
| STM32MP157C-DK2 | on hand |
| MB1407 DSI display, 480x800 (comes with the DK2) | on hand, the robot's screen |
| USB camera | on hand, not used yet |
| SimpleFOC board | to order |
| BLDC motor (x1 to start) | to order |
| IMU, encoders, second motor | undecided |
