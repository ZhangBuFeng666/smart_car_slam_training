#!/usr/bin/env python
# encoding: utf-8

#public lib
import sys
import math
import random
import threading
from math import pi
from time import sleep
from Rosmaster_Lib import Rosmaster

#ros lib
import rclpy
from rclpy.node import Node
from rclpy.callback_groups import MutuallyExclusiveCallbackGroup
from rclpy.executors import MultiThreadedExecutor
from std_msgs.msg import String,Float32,Int32,Bool
from geometry_msgs.msg import Twist
from sensor_msgs.msg import Imu,MagneticField, JointState
from rclpy.clock import Clock

#from dynamic_reconfigure.server import Server
car_type_dic={
    'R2':5,
    'X3':1,
    'NONE':-1
}
class icar_driver(Node):
	def __init__(self, name):
		super().__init__(name)
		global car_type_dic
		self.RA2DE = 180 / pi
		self.car = Rosmaster()
		self.car_io_lock = threading.Lock()
		self.command_callback_group = MutuallyExclusiveCallbackGroup()
		self.cached_version = 1.0
		self.version_poll_ticks = 0
		self.car.set_car_type(1)
		#get parameter
		self.declare_parameter('car_type', 'X3')
		self.car_type = self.get_parameter('car_type').get_parameter_value().string_value
		print (self.car_type)
		self.declare_parameter('imu_link', 'imu_link')
		self.imu_link = self.get_parameter('imu_link').get_parameter_value().string_value
		print (self.imu_link)
		self.declare_parameter('Prefix', "")
		self.Prefix = self.get_parameter('Prefix').get_parameter_value().string_value
		print (self.Prefix)
		self.declare_parameter('xlinear_limit', 1.0)
		self.xlinear_limit = self.get_parameter('xlinear_limit').get_parameter_value().double_value
		print (self.xlinear_limit)
		self.declare_parameter('ylinear_limit', 1.0)
		self.ylinear_limit = self.get_parameter('ylinear_limit').get_parameter_value().double_value
		print (self.ylinear_limit)
		self.declare_parameter('angular_limit', 5.0)
		self.angular_limit = self.get_parameter('angular_limit').get_parameter_value().double_value
		print (self.angular_limit)
		# Rosmaster 3.3.1 reports the X3/ICM gyroscope in degrees per second,
		# while sensor_msgs/Imu requires radians per second.  Publishing the raw
		# number makes a harmless 0.6 deg/s zero offset look like 0.6 rad/s and
		# causes robot_localization (and therefore Gmapping) to rotate in place.
		self.declare_parameter('gyro_scale', pi / 180.0)
		self.gyro_scale = self.get_parameter('gyro_scale').get_parameter_value().double_value
		self.declare_parameter('gyro_bias_samples', 30)
		self.gyro_bias_samples = self.get_parameter('gyro_bias_samples').get_parameter_value().integer_value
		self.declare_parameter('gyro_deadband', 0.003)
		self.gyro_deadband = self.get_parameter('gyro_deadband').get_parameter_value().double_value
		self.gyro_bias_sum = [0.0, 0.0, 0.0]
		self.gyro_bias = [0.0, 0.0, 0.0]
		self.gyro_bias_count = 0
		self.gyro_bias_ready = self.gyro_bias_samples <= 0
		self.commanded_motion = (0.0, 0.0, 0.0)

		#create subcriber
		self.sub_cmd_vel = self.create_subscription(
			Twist, "cmd_vel", self.cmd_vel_callback, 10,
			callback_group=self.command_callback_group
		)
		self.sub_RGBLight = self.create_subscription(
			Int32, "RGBLight", self.RGBLightcallback, 100,
			callback_group=self.command_callback_group
		)
		self.sub_BUzzer = self.create_subscription(
			Bool, "Buzzer", self.Buzzercallback, 100,
			callback_group=self.command_callback_group
		)

		#create publisher
		self.EdiPublisher = self.create_publisher(Float32,"edition",100)
		self.volPublisher = self.create_publisher(Float32,"voltage",100)
		self.staPublisher = self.create_publisher(JointState,"joint_states",100)
		self.velPublisher = self.create_publisher(Twist,"vel_raw",50)
		self.imuPublisher = self.create_publisher(Imu,"imu/data_raw",100)
		self.magPublisher = self.create_publisher(MagneticField,"imu/mag",100)

		#create timer
		self.timer = self.create_timer(0.1, self.pub_data)

		#create and init variable
		self.edition = Float32()
		self.edition.data = 1.0
		self.car.create_receive_threading()
	#callback function
	def cmd_vel_callback(self,msg):
        # 小车运动控制，订阅者回调函数
        # Car motion control, subscriber callback function
		if not isinstance(msg, Twist): return
        # 下发线速度和角速度
        # Issue linear vel and angular vel
		vx = msg.linear.x*1.0
        #vy = msg.linear.y/1000.0*180.0/3.1416    #Radian system
		vy = msg.linear.y*1.0
		angular = msg.angular.z*1.0     # wait for chang
		self.commanded_motion = (vx, vy, angular)
		with self.car_io_lock:
			self.car.set_car_motion(vx, vy, angular)
		'''print("cmd_vx: ",vx)
		print("cmd_vy: ",vy)
		print("cmd_angular: ",angular)'''
        #rospy.loginfo("nav_use_rot:{}".format(self.nav_use_rotvel))
        #print(self.nav_use_rotvel)
	def RGBLightcallback(self,msg):
        # 流水灯控制，服务端回调函数 RGBLight control
		if not isinstance(msg, Int32): return
		# print ("RGBLight: ", msg.data)
		with self.car_io_lock:
			for i in range(3): self.car.set_colorful_effect(msg.data, 6, parm=1)
	def Buzzercallback(self,msg):
		if not isinstance(msg, Bool): return
		with self.car_io_lock:
			if msg.data:
				for i in range(3): self.car.set_beep(1)
			else:
				for i in range(3): self.car.set_beep(0)

	def correct_gyroscope(self, gx, gy, gz, vx, vy, angular):
		"""Convert X3 gyro units and remove its stationary zero-rate offset."""
		values = [gx * self.gyro_scale, gy * self.gyro_scale, gz * self.gyro_scale]
		cmd_vx, cmd_vy, cmd_angular = self.commanded_motion
		stationary = (
			abs(vx) < 0.02 and abs(vy) < 0.02 and abs(angular) < 0.08 and
			abs(cmd_vx) < 0.02 and abs(cmd_vy) < 0.02 and abs(cmd_angular) < 0.08
		)
		if not self.gyro_bias_ready:
			if not stationary:
				self.gyro_bias_sum = [0.0, 0.0, 0.0]
				self.gyro_bias_count = 0
				return 0.0, 0.0, 0.0
			for index, value in enumerate(values):
				self.gyro_bias_sum[index] += value
			self.gyro_bias_count += 1
			if self.gyro_bias_count < self.gyro_bias_samples:
				return 0.0, 0.0, 0.0
			self.gyro_bias = [value / self.gyro_bias_count for value in self.gyro_bias_sum]
			self.gyro_bias_ready = True
			print("Gyroscope calibrated, bias(rad/s): %.6f %.6f %.6f" % tuple(self.gyro_bias))
		corrected = [value - bias for value, bias in zip(values, self.gyro_bias)]
		return tuple(0.0 if abs(value) < self.gyro_deadband else value for value in corrected)

	#pub data
	def pub_data(self):
		time_stamp = Clock().now()
		imu = Imu()
		twist = Twist()
		battery = Float32()
		edition = Float32()
		mag = MagneticField()
		state = JointState()
		state.header.stamp = time_stamp.to_msg()
		state.header.frame_id = "joint_states"
		if len(self.Prefix)==0:
			state.name = ["back_right_joint", "back_left_joint","front_left_steer_joint","front_left_wheel_joint",
							"front_right_steer_joint", "front_right_wheel_joint"]
		else:
			state.name = [self.Prefix+"back_right_joint",self.Prefix+ "back_left_joint",self.Prefix+"front_left_steer_joint",self.Prefix+"front_left_wheel_joint",
							self.Prefix+"front_right_steer_joint", self.Prefix+"front_right_wheel_joint"]

		#print ("mag: ",self.car.get_magnetometer_data())
		# Firmware version is effectively static. Querying it every 100 ms performs
		# synchronous serial I/O and can starve /cmd_vel while Nav2 is busy.
		if self.version_poll_ticks <= 0:
			with self.car_io_lock:
				version = self.car.get_version()*1.0
			if version >= 0:
				self.cached_version = version
			self.version_poll_ticks = 100
		else:
			self.version_poll_ticks -= 1
		edition.data = self.cached_version
		battery.data = self.car.get_battery_voltage()*1.0
		ax, ay, az = self.car.get_accelerometer_data()
		gx, gy, gz = self.car.get_gyroscope_data()
		mx, my, mz = self.car.get_magnetometer_data()
		mx = mx * 1.0
		my = my * 1.0
		mz = mz * 1.0
		vx, vy, angular = self.car.get_motion_data()
		gx, gy, gz = self.correct_gyroscope(gx, gy, gz, vx, vy, angular)
		'''print("vx: ",vx)
		print("vy: ",vy)
		print("angular: ",angular)'''
		# 发布陀螺仪的数据
		# Publish gyroscope data
		imu.header.stamp = time_stamp.to_msg()
		imu.header.frame_id = self.imu_link
		imu.linear_acceleration.x = ax*1.0
		imu.linear_acceleration.y = ay*1.0
		imu.linear_acceleration.z = az*1.0
		imu.angular_velocity.x = gx*1.0
		imu.angular_velocity.y = gy*1.0
		imu.angular_velocity.z = gz*1.0

		mag.header.stamp = time_stamp.to_msg()
		mag.header.frame_id = self.imu_link
		mag.magnetic_field.x = mx*1.0
		mag.magnetic_field.y = my*1.0
		mag.magnetic_field.z = mz*1.0

		# 将小车当前的线速度和角速度发布出去
		# Publish the current linear vel and angular vel of the car
		twist.linear.x = vx *1.0
		twist.linear.y = vy *1.0
		twist.angular.z = angular*1.0
		self.velPublisher.publish(twist)
		# print("ax: %.5f, ay: %.5f, az: %.5f" % (ax, ay, az))
		# print("gx: %.5f, gy: %.5f, gz: %.5f" % (gx, gy, gz))
		# print("mx: %.5f, my: %.5f, mz: %.5f" % (mx, my, mz))
		# rospy.loginfo("battery: {}".format(battery))
		# rospy.loginfo("vx: {}, vy: {}, angular: {}".format(twist.linear.x, twist.linear.y, twist.angular.z))
		self.imuPublisher.publish(imu)
		self.magPublisher.publish(mag)
		self.volPublisher.publish(battery)
		self.EdiPublisher.publish(edition)



def main():
	rclpy.init()
	driver = icar_driver('driver_node')
	executor = MultiThreadedExecutor(num_threads=2)
	executor.add_node(driver)
	try:
		executor.spin()
	finally:
		executor.shutdown()
		driver.destroy_node()
		rclpy.shutdown()

'''if __name__ == '__main__':
	main()'''
