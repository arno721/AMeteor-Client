#version 330 core

layout (location = 0) in vec4 Position;
layout (location = 1) in vec2 Local;
layout (location = 2) in vec4 Box;
layout (location = 3) in vec4 Box2;
layout (location = 4) in vec4 Params;
layout (location = 5) in vec4 Box3;
layout (location = 6) in vec4 Params3;
layout (location = 7) in vec4 ColorA;
layout (location = 8) in vec4 ColorB;
layout (location = 9) in vec4 ColorC;

layout (std140) uniform MeshData {
    mat4 u_Proj;
    mat4 u_ModelView;
};

out vec2 v_Local;
out vec4 v_Box;
out vec4 v_Box2;
out vec4 v_Params;
out vec4 v_Box3;
out vec4 v_Params3;
out vec4 v_ColorA;
out vec4 v_ColorB;
out vec4 v_ColorC;

void main() {
    gl_Position = u_Proj * u_ModelView * Position;

    v_Local = Local;
    v_Box = Box;
    v_Box2 = Box2;
    v_Params = Params;
    v_Box3 = Box3;
    v_Params3 = Params3;
    v_ColorA = ColorA;
    v_ColorB = ColorB;
    v_ColorC = ColorC;
}
