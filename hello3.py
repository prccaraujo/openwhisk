import sys

def main(args):
    file_object = open("/home/text.txt", "r")
    new_file = open("/home/response_file.txt", "w")
    new_file.write("WAS ABLE TO READ THE OTHER FILE")
    new_file.write(args.get("name", "stranger"))
    new_file.write(file_object.readline())
    new_file.close()
    file_object.close()
    name = args.get("name", "stranger")
    greeting = "Hello " + name + "!"
    response = {"greeting": greeting} 
    
    print(response)
    return response
